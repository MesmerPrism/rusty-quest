#include "gpu_hand_workload.h"
#include "gpu_hand_math.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

namespace rq::workload {
namespace {
using hand_math::finitePose;
using hand_math::grafts;
using hand_math::validHand;

const uint32_t skinCode[] =
#include "gpu_hand_skinning.comp.inc"
    ;
const uint32_t packCode[] =
#include "gpu_hand_pack.comp.inc"
    ;
struct SourceVertex {
  float position[4];
  uint32_t joints[4];
  float weights[4];
};
using hand_math::GraftParams;
struct SkinPush {
  uint32_t dims[4];
  float rows[12];
};
struct PackPush {
  uint32_t counts[4], enabled[4];
};
static_assert(sizeof(SourceVertex) == 48 && sizeof(GraftParams) == 192 &&
                  sizeof(SkinPush) == 64 && sizeof(PackPush) == 32,
              "shader ABI");
void check(VkResult r, const char *op) {
  if (r != VK_SUCCESS)
    throw std::runtime_error(std::string(op) + ":" + std::to_string(r));
}
std::vector<uint8_t> asset(AAssetManager *manager, const std::string &path) {
  AAsset *a = AAssetManager_open(manager, path.c_str(), AASSET_MODE_BUFFER);
  if (!a)
    throw std::runtime_error("missing hand asset:" + path);
  const auto n = AAsset_getLength64(a);
  if (n <= 0 || n > 16 * 1024 * 1024) {
    AAsset_close(a);
    throw std::runtime_error("invalid hand asset length");
  }
  std::vector<uint8_t> bytes(static_cast<size_t>(n));
  size_t read = 0;
  while (read < bytes.size()) {
    const int got = AAsset_read(a, bytes.data() + read, bytes.size() - read);
    if (got <= 0)
      break;
    read += size_t(got);
  }
  AAsset_close(a);
  if (read != bytes.size())
    throw std::runtime_error("truncated hand asset");
  return bytes;
}
uint32_t word(const std::vector<uint8_t> &bytes, size_t offset) {
  uint32_t v = 0;
  if (offset + 4 > bytes.size())
    throw std::runtime_error("truncated hand metadata");
  std::memcpy(&v, bytes.data() + offset, 4);
  return v;
}
struct Buffer {
  VkBuffer handle = VK_NULL_HANDLE;
  VkDeviceMemory memory = VK_NULL_HANDLE;
  VkDeviceSize bytes = 0;
  void *mapped = nullptr;
};
struct Pipeline {
  VkDescriptorSetLayout descriptors = VK_NULL_HANDLE;
  VkPipelineLayout layout = VK_NULL_HANDLE;
  VkPipeline pipeline = VK_NULL_HANDLE;
};
struct Rig {
  uint32_t vertices = 0, triangles = 0, bindCount = 0;
  Buffer source, bind, sources, indices, poses, tips, skinned, graft;
  VkDescriptorSet skinSet = VK_NULL_HANDLE, packSet = VK_NULL_HANDLE;
};
} // namespace
struct HandWorkload::Impl {
  GpuContext gpu;
  Rig rigs[2];
  Buffer expanded;
  Pipeline skin, pack;
  VkDescriptorPool pool = VK_NULL_HANDLE;
  HandOutput published;
  bool destroyed = false;
  explicit Impl(const GpuContext &c) : gpu(c) {}
  Buffer buffer(VkDeviceSize bytes, const void *initial = nullptr) {
    Buffer b;
    b.bytes = bytes;
    VkBufferCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    info.size = bytes;
    info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    check(vkCreateBuffer(gpu.device, &info, nullptr, &b.handle), "hand buffer");
    VkMemoryRequirements req{};
    vkGetBufferMemoryRequirements(gpu.device, b.handle, &req);
    uint32_t type = UINT32_MAX;
    for (uint32_t i = 0; i < gpu.memoryProperties.memoryTypeCount; ++i) {
      const auto flags = gpu.memoryProperties.memoryTypes[i].propertyFlags;
      if ((req.memoryTypeBits & (1u << i)) &&
          (flags & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                    VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) ==
              (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
               VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
        type = i;
        break;
      }
    }
    if (type == UINT32_MAX) {
      vkDestroyBuffer(gpu.device, b.handle, nullptr);
      throw std::runtime_error("hand coherent memory unavailable");
    }
    VkMemoryAllocateInfo alloc{};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.allocationSize = req.size;
    alloc.memoryTypeIndex = type;
    try {
      check(vkAllocateMemory(gpu.device, &alloc, nullptr, &b.memory),
            "hand allocate");
      check(vkBindBufferMemory(gpu.device, b.handle, b.memory, 0), "hand bind");
      check(vkMapMemory(gpu.device, b.memory, 0, bytes, 0, &b.mapped),
            "hand map");
      if (initial)
        std::memcpy(b.mapped, initial, size_t(bytes));
      else
        std::memset(b.mapped, 0, size_t(bytes));
    } catch (...) {
      destroy(b);
      throw;
    }
    return b;
  }
  void destroy(Buffer &b) {
    if (b.mapped)
      vkUnmapMemory(gpu.device, b.memory);
    if (b.handle)
      vkDestroyBuffer(gpu.device, b.handle, nullptr);
    if (b.memory)
      vkFreeMemory(gpu.device, b.memory, nullptr);
    b = {};
  }
  void pipeline(Pipeline &p, const uint32_t *code, size_t bytes,
                const std::vector<uint32_t> &bindings, uint32_t pushBytes) {
    std::vector<VkDescriptorSetLayoutBinding> rows;
    for (auto binding : bindings) {
      VkDescriptorSetLayoutBinding row{};
      row.binding = binding;
      row.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
      row.descriptorCount = 1;
      row.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
      rows.push_back(row);
    }
    VkDescriptorSetLayoutCreateInfo d{};
    d.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    d.bindingCount = uint32_t(rows.size());
    d.pBindings = rows.data();
    check(vkCreateDescriptorSetLayout(gpu.device, &d, nullptr, &p.descriptors),
          "hand descriptor layout");
    VkPushConstantRange range{VK_SHADER_STAGE_COMPUTE_BIT, 0, pushBytes};
    VkPipelineLayoutCreateInfo l{};
    l.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    l.setLayoutCount = 1;
    l.pSetLayouts = &p.descriptors;
    l.pushConstantRangeCount = 1;
    l.pPushConstantRanges = &range;
    check(vkCreatePipelineLayout(gpu.device, &l, nullptr, &p.layout),
          "hand layout");
    VkShaderModuleCreateInfo m{};
    m.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    m.codeSize = bytes;
    m.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    check(vkCreateShaderModule(gpu.device, &m, nullptr, &module),
          "hand shader");
    VkComputePipelineCreateInfo c{};
    c.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    c.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    c.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    c.stage.module = module;
    c.stage.pName = "main";
    c.layout = p.layout;
    const auto result = vkCreateComputePipelines(gpu.device, VK_NULL_HANDLE, 1,
                                                 &c, nullptr, &p.pipeline);
    vkDestroyShaderModule(gpu.device, module, nullptr);
    check(result, "hand pipeline");
  }
  VkDescriptorSet
  set(const Pipeline &p,
      const std::vector<std::pair<uint32_t, const Buffer *>> &buffers) {
    VkDescriptorSetAllocateInfo a{};
    a.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    a.descriptorPool = pool;
    a.descriptorSetCount = 1;
    a.pSetLayouts = &p.descriptors;
    VkDescriptorSet result = VK_NULL_HANDLE;
    check(vkAllocateDescriptorSets(gpu.device, &a, &result), "hand descriptor");
    std::vector<VkDescriptorBufferInfo> info(buffers.size());
    std::vector<VkWriteDescriptorSet> writes(buffers.size());
    for (size_t i = 0; i < buffers.size(); ++i) {
      info[i] = {buffers[i].second->handle, 0, buffers[i].second->bytes};
      auto &w = writes[i];
      w.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
      w.dstSet = result;
      w.dstBinding = buffers[i].first;
      w.descriptorCount = 1;
      w.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
      w.pBufferInfo = &info[i];
    }
    vkUpdateDescriptorSets(gpu.device, uint32_t(writes.size()), writes.data(),
                           0, nullptr);
    return result;
  }
  void initialize(AAssetManager *assets) {
    auto meta = asset(assets, "workload/hands/metadata.bin");
    if (meta.size() != 48 || word(meta, 0) != 0x31484752 || word(meta, 4) != 1)
      throw std::runtime_error("hand metadata schema");
    for (unsigned hand = 0; hand < 2; ++hand) {
      auto &r = rigs[hand];
      const size_t off = 8 + 20 * hand;
      if (word(meta, off) != hand || word(meta, off + 16) != 21)
        throw std::runtime_error("hand metadata identity");
      r.vertices = word(meta, off + 4);
      r.triangles = word(meta, off + 8);
      r.bindCount = word(meta, off + 12);
      if (r.vertices == 0 || r.vertices > 10000 || r.triangles == 0 ||
          r.triangles > 10000 || r.bindCount != 26)
        throw std::runtime_error("hand metadata counts");
      const std::string path =
          std::string("workload/hands/") + (hand == 0 ? "left/" : "right/");
      auto vertices = asset(assets, path + "vertices.bin"),
           bind = asset(assets, path + "bind_poses.bin"),
           sources = asset(assets, path + "bind_sources.bin"),
           triangles = asset(assets, path + "triangles.bin");
      if (vertices.size() != r.vertices * 48ull ||
          bind.size() != r.bindCount * 32ull ||
          sources.size() != r.bindCount * 16ull ||
          triangles.size() != r.triangles * 16ull)
        throw std::runtime_error("hand payload lengths");
      for (uint32_t i = 0; i < r.vertices; ++i) {
        SourceVertex v;
        std::memcpy(&v, vertices.data() + 48 * i, 48);
        for (float f : v.position)
          if (!std::isfinite(f))
            throw std::runtime_error("nonfinite bind vertex");
        float total = 0;
        for (int j = 0; j < 4; ++j) {
          if (v.joints[j] >= r.bindCount || !std::isfinite(v.weights[j]) ||
              v.weights[j] < 0)
            throw std::runtime_error("invalid skin binding");
          total += v.weights[j];
        }
        if (!std::isfinite(total) || total <= 0)
          throw std::runtime_error("invalid skin weight sum");
      }
      for (uint32_t i = 0; i < r.bindCount; ++i) {
        HandPose pose;
        std::memcpy(&pose, bind.data() + 32 * i, 32);
        if (!finitePose(pose))
          throw std::runtime_error("invalid bind pose");
        const size_t j = 16 * i;
        const auto kind = word(sources, j);
        if (kind > 1 || (kind == 0 && word(sources, j + 4) >= 21) ||
            (kind == 1 &&
             (word(sources, j + 8) >= 5 || word(sources, j + 12) >= 21)))
          throw std::runtime_error("invalid bind source");
      }
      for (uint32_t i = 0; i < r.triangles; ++i)
        for (int j = 0; j < 3; ++j)
          if (word(triangles, 16 * i + 4 * j) >= r.vertices)
            throw std::runtime_error("invalid triangle index");
      r.source = buffer(vertices.size(), vertices.data());
      r.bind = buffer(bind.size(), bind.data());
      r.sources = buffer(sources.size(), sources.data());
      r.indices = buffer(triangles.size(), triangles.data());
      r.poses = buffer(21 * 32);
      r.tips = buffer(32);
      r.skinned = buffer(r.vertices * 16);
      r.graft = buffer(192);
    }
    published.leftCount = rigs[0].triangles * 3;
    published.rightCount = rigs[1].triangles * 3;
    published.baseCount = published.leftCount + published.rightCount;
    published.graftCount = published.baseCount * 5;
    expanded =
        buffer(VkDeviceSize(published.baseCount + published.graftCount) * 32);
    published.buffer = expanded.handle;
    published.bytes = expanded.bytes;
    pipeline(skin, skinCode, sizeof(skinCode), {0, 2, 3, 4, 5, 6}, 64);
    pipeline(pack, packCode, sizeof(packCode), {0, 1, 2, 3}, 32);
    VkDescriptorPoolSize size{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 20};
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 4;
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes = &size;
    check(vkCreateDescriptorPool(gpu.device, &poolInfo, nullptr, &pool),
          "hand pool");
    for (auto &r : rigs) {
      r.skinSet = set(skin, {{0, &r.source},
                             {2, &r.poses},
                             {3, &r.tips},
                             {4, &r.bind},
                             {5, &r.sources},
                             {6, &r.skinned}});
      r.packSet = set(
          pack,
          {{0, &r.skinned}, {1, &r.indices}, {2, &r.graft}, {3, &expanded}});
    }
    published.initialized = true;
  }
  void destroy() {
    if (destroyed)
      return;
    destroyed = true;
    published = {};
    if (pool)
      vkDestroyDescriptorPool(gpu.device, pool, nullptr);
    for (auto *p : {&skin, &pack}) {
      if (p->pipeline)
        vkDestroyPipeline(gpu.device, p->pipeline, nullptr);
      if (p->layout)
        vkDestroyPipelineLayout(gpu.device, p->layout, nullptr);
      if (p->descriptors)
        vkDestroyDescriptorSetLayout(gpu.device, p->descriptors, nullptr);
    }
    for (auto &r : rigs)
      for (auto *b : {&r.source, &r.bind, &r.sources, &r.indices, &r.poses,
                      &r.tips, &r.skinned, &r.graft})
        destroy(*b);
    destroy(expanded);
  }
};
HandWorkload::HandWorkload(const GpuContext &context, AAssetManager *assets)
    : impl_(std::make_unique<Impl>(context)) {
  try {
    impl_->initialize(assets);
  } catch (...) {
    impl_->destroy();
    throw;
  }
}
HandWorkload::~HandWorkload() =
    default; // Explicit retirement API owns Vulkan destruction.
HandOutput HandWorkload::output() const { return impl_->published; }
void HandWorkload::destroyAfterRetirement() { impl_->destroy(); }
void HandWorkload::record(VkCommandBuffer cmd, const FrameContext &frame,
                          const LiveHandPair &input, bool drawHands,
                          bool drawGrafts, float scale) {
  auto &p = *impl_;
  if (p.destroyed)
    throw std::runtime_error("hand workload destroyed");
  if (frame.generation == 0 || frame.generation > UINT32_MAX ||
      frame.submission == 0 || frame.submission > UINT32_MAX)
    throw std::runtime_error("hand pack identity outside packet domain");
  // The host has retired this slot. Also state the GPU reuse dependency from
  // previous atlas reads/compute writes to this frame's skin/pack writes.
  VkMemoryBarrier reuse{};
  reuse.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
  reuse.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
  reuse.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
  vkCmdPipelineBarrier(cmd,
                       VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT |
                           VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                       VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &reuse, 0,
                       nullptr, 0, nullptr);
  p.published.input = input;
  bool active[2] = {validHand(input.hands[0]), validHand(input.hands[1])};
  if (input.generation != frame.generation || input.xrTime <= 0)
    active[0] = active[1] = false;
  for (unsigned hand = 0; hand < 2; ++hand) {
    auto &r = p.rigs[hand];
    p.published.input.hands[hand].active = active[hand];
    std::memcpy(r.poses.mapped, input.hands[hand].joints.data(), 21 * 32);
    std::memcpy(r.tips.mapped, input.hands[hand].tipLengths.data(), 32);
    GraftParams params{};
    if (active[0] && active[1])
      params = grafts(input.hands[hand], input.hands[1 - hand], scale);
    std::memcpy(r.graft.mapped, &params, sizeof(params));
  }
  VkMemoryBarrier host{};
  host.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
  host.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
  host.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
  vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_HOST_BIT,
                       VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &host, 0,
                       nullptr, 0, nullptr);
  for (unsigned hand = 0; hand < 2; ++hand) {
    auto &r = p.rigs[hand];
    if (active[hand]) {
      SkinPush push{};
      push.dims[0] = r.vertices;
      vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, p.skin.pipeline);
      vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                              p.skin.layout, 0, 1, &r.skinSet, 0, nullptr);
      vkCmdPushConstants(cmd, p.skin.layout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                         sizeof(push), &push);
      vkCmdDispatch(cmd, (r.vertices + 63) / 64, 1, 1);
    }
  }
  VkMemoryBarrier compute{};
  compute.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
  compute.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
  compute.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
  vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                       VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &compute, 0,
                       nullptr, 0, nullptr);
  for (unsigned hand = 0; hand < 2; ++hand) {
    auto &r = p.rigs[hand];
    PackPush push{
        {r.triangles, hand == 0 ? 0 : p.published.leftCount,
         p.published.baseCount + (hand == 0 ? 0 : 5 * p.published.leftCount),
         uint32_t(active[hand])},
        {uint32_t(drawHands), uint32_t(drawGrafts && active[0] && active[1]),
         uint32_t(frame.submission), uint32_t(frame.generation)}};
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, p.pack.pipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, p.pack.layout,
                            0, 1, &r.packSet, 0, nullptr);
    vkCmdPushConstants(cmd, p.pack.layout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                       sizeof(push), &push);
    vkCmdDispatch(cmd, (r.triangles * 18 + 63) / 64, 1, 1);
  }
  vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                       VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT |
                           VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                       0, 1, &compute, 0, nullptr, 0, nullptr);
}
} // namespace rq::workload

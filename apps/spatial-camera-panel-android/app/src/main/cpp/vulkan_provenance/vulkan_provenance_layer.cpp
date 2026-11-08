// SPDX-License-Identifier: AGPL-3.0-or-later
// Optional debug loader layer. Raw handles belong to participating downstream
// dispatch chains only; neither SDK ownership nor VVL handle domains are inferred.
#define VK_NO_PROTOTYPES
#include <vulkan/vk_layer.h>
#include "provenance_recorder.hpp"

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstring>
#include <initializer_list>
#include <memory>
#include <mutex>
#include <thread>
#include <type_traits>
#include <unordered_map>
#ifdef __ANDROID__
#include <android/log.h>
#include <cerrno>
#include <cstdio>
#include <fcntl.h>
#include <dlfcn.h>
#include <sys/syscall.h>
#include <sys/system_properties.h>
#include <unistd.h>
#endif

#ifndef RQ_VULKAN_PROVENANCE_APP_ID
#error RQ_VULKAN_PROVENANCE_APP_ID must be the selected application ID
#endif

extern "C" {
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance, const char*);
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice, const char*);
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vk_layerGetPhysicalDeviceProcAddr(VkInstance, const char*);
}

namespace rqvp_layer {
constexpr const char* kLayerName = "VK_LAYER_RUSTY_QUEST_provenance";
constexpr uint32_t kMaxDetails = 65536;
template<class T> uint64_t bits(T value) {
  if constexpr (std::is_pointer_v<T>) return static_cast<uint64_t>(reinterpret_cast<uintptr_t>(value));
  else return static_cast<uint64_t>(value);
}
uint64_t now() {
  return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(
      std::chrono::steady_clock::now().time_since_epoch()).count());
}
uint64_t tid() {
#ifdef __ANDROID__
  return static_cast<uint64_t>(syscall(SYS_gettid));
#else
  return static_cast<uint64_t>(std::hash<std::thread::id>{}(std::this_thread::get_id()));
#endif
}
bool runtimeSelected(const char* property, const char* process) {
  return property && process && RQ_VULKAN_PROVENANCE_APP_ID[0] &&
      std::strcmp(property, "1") == 0 && std::strcmp(process, RQ_VULKAN_PROVENANCE_APP_ID) == 0;
}
struct Capture {
  std::once_flag admission;
  std::unique_ptr<rqvp::Recorder> recorder;
#ifdef __ANDROID__
  int fd = -1;
#endif
};
Capture& capture() { static Capture value; return value; }
#ifdef __ANDROID__
bool fileSink(const char* data, size_t bytes, void* user) {
  const int fd = *static_cast<int*>(user);
  while (bytes) {
    const ssize_t written = write(fd, data, bytes);
    if (written < 0 && errno == EINTR) continue;
    if (written <= 0) return false;
    data += written;
    bytes -= static_cast<size_t>(written);
  }
  return true;
}
#endif
// Called ONLY by first CreateInstance. Off/unavailable admission never retries.
void freezeAdmission() {
  auto& state = capture();
  std::call_once(state.admission, [&state] {
#ifdef __ANDROID__
    char property[PROP_VALUE_MAX]{};
    __system_property_get("debug.rustyquest.vulkan_provenance.enabled", property);
    char process[256]{};
    const int cmd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    const ssize_t count = cmd < 0 ? -1 : read(cmd, process, sizeof(process));
    if (cmd >= 0) close(cmd);
    if (count <= 0 || !std::memchr(process, '\0', static_cast<size_t>(count)) ||
        !runtimeSelected(property, process)) return;
    char path[512]{};
    const int size = std::snprintf(path, sizeof(path),
        "/data/user/0/%s/files/rusty-quest-vulkan-provenance-%ld.jsonl",
        RQ_VULKAN_PROVENANCE_APP_ID, static_cast<long>(getpid()));
    if (size <= 0 || static_cast<size_t>(size) >= sizeof(path)) return;
    state.fd = open(path, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (state.fd < 0) {
      __android_log_print(ANDROID_LOG_WARN, "RustyQuestVkProvenance",
          "capture-unavailable fixed-sink-open-failed errno=%d admissionFrozen=true", errno);
      return;
    }
    state.recorder = std::make_unique<rqvp::Recorder>(fileSink, &state.fd);
    state.recorder->start(true, now(), static_cast<uint64_t>(getpid()));
    __android_log_print(ANDROID_LOG_INFO, "RustyQuestVkProvenance",
        "capture-open admissionFrozen=true dispatchCoverage=participating-chain-only handleDomain=unverified");
#endif
  });
}
struct Instance {
  VkInstance handle;
  uint64_t generation;
  PFN_vkGetInstanceProcAddr gipa;
  PFN_GetPhysicalDeviceProcAddr physical;
};
struct Device {
  VkDevice handle;
  uint64_t generation;
  std::shared_ptr<Instance> instance;
  PFN_vkGetDeviceProcAddr gdpa;
};
using InstancePtr = std::shared_ptr<Instance>;
using DevicePtr = std::shared_ptr<Device>;
std::mutex dispatchMutex;
std::unordered_map<void*, InstancePtr> instances;
std::unordered_map<void*, DevicePtr> devices;
std::atomic<uint64_t> generations{1};
std::atomic<PFN_vkGetInstanceProcAddr> globalGipa{nullptr};
template<class T> void* key(T handle) { return handle ? *reinterpret_cast<void* const*>(handle) : nullptr; }
template<class T> InstancePtr instanceFor(T handle) {
  if (!handle) return {};
  std::lock_guard<std::mutex> lock(dispatchMutex);
  auto it = instances.find(key(handle));
  return it == instances.end() ? InstancePtr{} : it->second;
}
template<class T> DevicePtr deviceFor(T handle) {
  if (!handle) return {};
  std::lock_guard<std::mutex> lock(dispatchMutex);
  auto it = devices.find(key(handle));
  return it == devices.end() ? DevicePtr{} : it->second;
}
template<class T> T next(const DevicePtr& d, const char* name) {
  return d ? reinterpret_cast<T>(d->gdpa(d->handle, name)) : nullptr;
}
struct Fields {
  std::array<rqvp::Field, 48> data{};
  size_t count = 0;
  void add(std::initializer_list<rqvp::Field> extra) {
    for (const auto& field : extra) if (count < data.size()) data[count++] = field;
  }
};
struct Event {
  const char* op;
  rqvp::Recorder* recorder;
  uint64_t call = 0;
  Fields context;
  Event(const char* operation, const InstancePtr& i, const DevicePtr& d,
        std::initializer_list<rqvp::Field> extra = {}) : op(operation), recorder(capture().recorder.get()) {
    context.add({{"instance", i ? bits(i->handle) : 0}, {"instanceGeneration", i ? i->generation : 0},
        {"device", d ? bits(d->handle) : 0}, {"deviceGeneration", d ? d->generation : 0}});
    Fields fields = context;
    fields.add(extra);
    if (recorder) call = recorder->begin(now(), tid(), op, fields.data.data(), fields.count);
  }
  Event(const char* operation, const DevicePtr& d, std::initializer_list<rqvp::Field> extra = {})
      : Event(operation, d ? d->instance : InstancePtr{}, d, extra) {}
  void detail(const char* operation, std::initializer_list<rqvp::Field> extra) const {
    if (!recorder || !call) return;
    Fields fields = context;
    fields.add({{"parentCall", call}});
    fields.add(extra);
    recorder->note(now(), tid(), operation, fields.data.data(), fields.count);
  }
  void finish(VkResult result, std::initializer_list<rqvp::Field> extra = {}) const {
    if (!recorder || !call) return;
    Fields fields = context;
    fields.add(extra);
    recorder->end(now(), tid(), op, call, static_cast<int32_t>(result), fields.data.data(), fields.count);
  }
  void gap(uint64_t reason) const { detail("coverage_gap", {{"gapReason", reason}}); }
};
// Stable byte hash identifies debug names/function names without asserting ownership.
uint64_t hashBytes(const char* text, size_t count) {
  uint64_t value = 14695981039346656037ULL;
  for (size_t n = 0; n < count; ++n) { value ^= static_cast<unsigned char>(text[n]); value *= 1099511628211ULL; }
  return value;
}
void target(const Event& event, const char* name, PFN_vkVoidFunction pointer) {
  if (!pointer || !event.recorder || !event.call) return;
  const uint64_t address = bits(pointer);
  uint64_t base = 0, pathHash = 0, pathBytes = 0;
#ifdef __ANDROID__
  Dl_info info{};
  if (dladdr(reinterpret_cast<const void*>(static_cast<uintptr_t>(address)), &info)) {
    base = bits(info.dli_fbase);
    if (info.dli_fname) {
      while (pathBytes < 4096 && info.dli_fname[pathBytes]) ++pathBytes;
      pathHash = hashBytes(info.dli_fname, static_cast<size_t>(pathBytes));
      __android_log_print(ANDROID_LOG_INFO, "RustyQuestVkProvenance",
          "dispatch-target fn=%s address=0x%llx moduleBase=0x%llx pathHash=%llu modulePath=%.256s pathBytes=%llu handleDomain=unverified",
          name, static_cast<unsigned long long>(address), static_cast<unsigned long long>(base),
          static_cast<unsigned long long>(pathHash), info.dli_fname, static_cast<unsigned long long>(pathBytes));
    }
  }
#endif
  event.detail("dispatch_target", {{"fn_hash", hashBytes(name, std::strlen(name))}, {"address", address},
      {"module_base", base}, {"offset", base && address >= base ? address-base : 0}, {"path_hash", pathHash}, {"path_bytes", pathBytes}});
}
// A scalar pointer is not provenance. Decode the relevant allocation/binding
// structures; every unparsed structure or overlong chain remains an explicit gap.
void allocationChain(const Event& event, const void* pNext) {
  if (!event.recorder || !event.call) return;
  auto* current = reinterpret_cast<const VkBaseInStructure*>(pNext);
  uint32_t count = 0;
  for (; current && count < 64; current = current->pNext, ++count) {
    event.detail("allocation_extension", {{"chainIndex", count}, {"structureType", bits(current->sType)}});
    switch (current->sType) {
      case VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO: {
        const auto* info = reinterpret_cast<const VkMemoryDedicatedAllocateInfo*>(current);
        event.detail("memory_dedicated", {{"image", bits(info->image)}, {"buffer", bits(info->buffer)}}); break;
      }
      case VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO: {
        const auto* info = reinterpret_cast<const VkMemoryAllocateFlagsInfo*>(current);
        event.detail("memory_allocate_flags", {{"flags", info->flags}, {"deviceMask", info->deviceMask}}); break;
      }
      case VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR: {
        const auto* info = reinterpret_cast<const VkImportMemoryFdInfoKHR*>(current);
        event.detail("memory_import_fd", {{"handleType", bits(info->handleType)}, {"fd", bits(info->fd)}}); break;
      }
      case VK_STRUCTURE_TYPE_IMPORT_MEMORY_HOST_POINTER_INFO_EXT: {
        const auto* info = reinterpret_cast<const VkImportMemoryHostPointerInfoEXT*>(current);
        event.detail("memory_import_host", {{"handleType", bits(info->handleType)}, {"hostPointer", bits(info->pHostPointer)}}); break;
      }
      case VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO: {
        const auto* info = reinterpret_cast<const VkExportMemoryAllocateInfo*>(current);
        event.detail("memory_export", {{"handleTypes", info->handleTypes}}); break;
      }
      default: event.gap(6); break;
    }
  }
  if (current) event.gap(2);
}
void bindingChain(const Event& event, uint32_t bindIndex, const void* pNext) {
  if (!event.recorder || !event.call) return;
  auto* current = reinterpret_cast<const VkBaseInStructure*>(pNext);
  uint32_t count = 0;
  for (; current && count < 64; current = current->pNext, ++count) {
    event.detail("binding_extension", {{"bindIndex", bindIndex}, {"chainIndex", count}, {"structureType", bits(current->sType)}});
    switch (current->sType) {
      case VK_STRUCTURE_TYPE_BIND_IMAGE_PLANE_MEMORY_INFO: {
        const auto* info = reinterpret_cast<const VkBindImagePlaneMemoryInfo*>(current);
        event.detail("image_memory_plane", {{"bindIndex", bindIndex}, {"planeAspect", bits(info->planeAspect)}}); break;
      }
      case VK_STRUCTURE_TYPE_BIND_IMAGE_MEMORY_SWAPCHAIN_INFO_KHR: {
        const auto* info = reinterpret_cast<const VkBindImageMemorySwapchainInfoKHR*>(current);
        event.detail("image_memory_swapchain", {{"bindIndex", bindIndex}, {"swapchain", bits(info->swapchain)}, {"imageIndex", info->imageIndex}}); break;
      }
      default: event.gap(6); break;
    }
  }
  if (current) event.gap(2);
}
void names(const Event& e, VkObjectType type, uint64_t object, const char* name) {
  if (!e.recorder || !e.call) return;
  size_t count = 0;
  if (name) while (count < 4096 && name[count]) ++count;
  e.detail("debug_name", {{"objectType", bits(type)}, {"object", object}, {"nameBytes", count},
      {"nameHashFNV64", name ? hashBytes(name, count) : 0}, {"nameNull", name ? 0U : 1U}});
  if (count == 4096) e.gap(1); // name truncated; never silently claim exact name coverage
}
template<class T> T* chain(const void* pNext, VkStructureType type) {
  auto* current = reinterpret_cast<const VkBaseInStructure*>(pNext);
  while (current) {
    if (current->sType == type) {
      auto* candidate = reinterpret_cast<const T*>(current);
      if (candidate->function == VK_LAYER_LINK_INFO) return const_cast<T*>(candidate);
    }
    current = current->pNext;
  }
  return nullptr;
}
} // namespace rqvp_layer

using namespace rqvp_layer;

extern "C" {
VKAPI_ATTR VkResult VKAPI_CALL vkCreateInstance(const VkInstanceCreateInfo* info,
    const VkAllocationCallbacks* allocator, VkInstance* output) {
  freezeAdmission();
  auto* link = info ? chain<VkLayerInstanceCreateInfo>(info->pNext, VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO) : nullptr;
  if (!link || !link->u.pLayerInfo) return VK_ERROR_INITIALIZATION_FAILED;
  const auto gipa = link->u.pLayerInfo->pfnNextGetInstanceProcAddr;
  const auto physical = link->u.pLayerInfo->pfnNextGetPhysicalDeviceProcAddr;
  const auto create = gipa ? reinterpret_cast<PFN_vkCreateInstance>(gipa(VK_NULL_HANDLE, "vkCreateInstance")) : nullptr;
  if (!create) return VK_ERROR_INITIALIZATION_FAILED;
  globalGipa.store(gipa);
  link->u.pLayerInfo = link->u.pLayerInfo->pNext; // standard loader-chain consumption; original pNext unchanged
  Event e("vkCreateInstance", InstancePtr{}, DevicePtr{}, {{"createInfo", bits(info)}, {"pNextPresent", info->pNext ? 1U : 0U},
      {"allocator", bits(allocator)}, {"enabledLayerCount", info->enabledLayerCount},
      {"enabledExtensionCount", info->enabledExtensionCount}});
  const VkResult result = create(info, allocator, output);
  uint64_t generation = 0;
  if (result == VK_SUCCESS && output && *output) {
    generation = generations.fetch_add(1);
    auto state = std::make_shared<Instance>(Instance{*output, generation, gipa, physical});
    { std::lock_guard<std::mutex> lock(dispatchMutex); instances[key(*output)] = state; }
    if (capture().recorder) {
      Event chainEvent("instance_dispatch", state, {});
      target(chainEvent, "nextGIPA", reinterpret_cast<PFN_vkVoidFunction>(gipa));
      target(chainEvent, "nextCreateInstance", reinterpret_cast<PFN_vkVoidFunction>(create));
      chainEvent.finish(VK_SUCCESS);
    }
  }
  e.finish(result, {{"createdInstance", result == VK_SUCCESS && output ? bits(*output) : 0}, {"createdInstanceGeneration", generation}});
  return result;
}
VKAPI_ATTR void VKAPI_CALL vkDestroyInstance(VkInstance instance, const VkAllocationCallbacks* allocator) {
  auto state = instanceFor(instance);
  auto destroy = state ? reinterpret_cast<PFN_vkDestroyInstance>(state->gipa(instance, "vkDestroyInstance")) : nullptr;
  if (!destroy) return;
  const auto dispatchKey = key(instance);
  Event e("vkDestroyInstance", state, {}, {{"allocator", bits(allocator)}});
  destroy(instance, allocator);
  { std::lock_guard<std::mutex> lock(dispatchMutex); auto it = instances.find(dispatchKey);
    if (it != instances.end() && it->second == state) instances.erase(it); }
  e.finish(VK_SUCCESS);
}
VKAPI_ATTR VkResult VKAPI_CALL vkCreateDevice(VkPhysicalDevice physical, const VkDeviceCreateInfo* info,
    const VkAllocationCallbacks* allocator, VkDevice* output) {
  auto instance = instanceFor(physical);
  auto* link = info ? chain<VkLayerDeviceCreateInfo>(info->pNext, VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO) : nullptr;
  if (!link || !link->u.pLayerInfo) return VK_ERROR_INITIALIZATION_FAILED;
  const auto gipa = link->u.pLayerInfo->pfnNextGetInstanceProcAddr;
  const auto gdpa = link->u.pLayerInfo->pfnNextGetDeviceProcAddr;
  const auto create = gipa ? reinterpret_cast<PFN_vkCreateDevice>(gipa(instance ? instance->handle : VK_NULL_HANDLE, "vkCreateDevice")) : nullptr;
  if (!create || !gdpa) return VK_ERROR_INITIALIZATION_FAILED;
  link->u.pLayerInfo = link->u.pLayerInfo->pNext;
  Event e("vkCreateDevice", instance, {}, {{"physicalDevice", bits(physical)}, {"createInfo", bits(info)},
      {"pNextPresent", info->pNext ? 1U : 0U}, {"allocator", bits(allocator)}, {"enabledExtensionCount", info->enabledExtensionCount}});
  const VkResult result = create(physical, info, allocator, output);
  uint64_t generation = 0;
  if (result == VK_SUCCESS && output && *output) {
    generation = generations.fetch_add(1);
    auto state = std::make_shared<Device>(Device{*output, generation, instance, gdpa});
    { std::lock_guard<std::mutex> lock(dispatchMutex); devices[key(*output)] = state; }
    if (capture().recorder) {
      Event chainEvent("device_dispatch", state);
      target(chainEvent, "nextGIPA", reinterpret_cast<PFN_vkVoidFunction>(gipa));
      target(chainEvent, "nextGDPA", reinterpret_cast<PFN_vkVoidFunction>(gdpa));
      target(chainEvent, "nextCreateDevice", reinterpret_cast<PFN_vkVoidFunction>(create));
      target(chainEvent, "nextCreateImage", gdpa(*output, "vkCreateImage"));
      target(chainEvent, "nextUpdateDescriptorSets", gdpa(*output, "vkUpdateDescriptorSets"));
      target(chainEvent, "nextFreeMemory", gdpa(*output, "vkFreeMemory"));
      chainEvent.finish(VK_SUCCESS);
    }
  }
  e.finish(result, {{"createdDevice", result == VK_SUCCESS && output ? bits(*output) : 0}, {"createdDeviceGeneration", generation}});
  return result;
}
VKAPI_ATTR void VKAPI_CALL vkDestroyDevice(VkDevice device, const VkAllocationCallbacks* allocator) {
  auto state = deviceFor(device);
  auto destroy = next<PFN_vkDestroyDevice>(state, "vkDestroyDevice");
  if (!destroy) return;
  const auto dispatchKey = key(device);
  Event e("vkDestroyDevice", state, {{"allocator", bits(allocator)}});
  destroy(device, allocator);
  { std::lock_guard<std::mutex> lock(dispatchMutex); auto it = devices.find(dispatchKey);
    if (it != devices.end() && it->second == state) devices.erase(it); }
  e.finish(VK_SUCCESS);
}

VKAPI_ATTR VkResult VKAPI_CALL vkCreateImage(VkDevice device, const VkImageCreateInfo* info,
    const VkAllocationCallbacks* allocator, VkImage* output) {
  auto state = deviceFor(device); auto fn = next<PFN_vkCreateImage>(state, "vkCreateImage");
  if (!fn) return VK_ERROR_INITIALIZATION_FAILED;
  Event e("vkCreateImage", state, {{"createInfo", bits(info)}, {"allocator", bits(allocator)}, {"nextFunction", bits(fn)}});
  if (info) e.detail("image_info", {{"pNextPresent", info->pNext ? 1U : 0U}, {"flags", info->flags}, {"imageType", bits(info->imageType)},
      {"format", bits(info->format)}, {"width", info->extent.width}, {"height", info->extent.height}, {"depth", info->extent.depth},
      {"mipLevels", info->mipLevels}, {"arrayLayers", info->arrayLayers}, {"samples", bits(info->samples)},
      {"tiling", bits(info->tiling)}, {"usage", info->usage}, {"sharingMode", bits(info->sharingMode)}, {"initialLayout", bits(info->initialLayout)}});
  const auto result = fn(device, info, allocator, output);
  e.finish(result, {{"image", result == VK_SUCCESS && output ? bits(*output) : 0}}); return result;
}
VKAPI_ATTR void VKAPI_CALL vkDestroyImage(VkDevice device, VkImage image, const VkAllocationCallbacks* allocator) {
  auto state = deviceFor(device); auto fn = next<PFN_vkDestroyImage>(state, "vkDestroyImage"); if (!fn) return;
  Event e("vkDestroyImage", state, {{"image", bits(image)}, {"allocator", bits(allocator)}});
  fn(device, image, allocator); e.finish(VK_SUCCESS);
}
VKAPI_ATTR VkResult VKAPI_CALL vkCreateImageView(VkDevice device, const VkImageViewCreateInfo* info,
    const VkAllocationCallbacks* allocator, VkImageView* output) {
  auto state = deviceFor(device); auto fn = next<PFN_vkCreateImageView>(state, "vkCreateImageView");
  if (!fn) return VK_ERROR_INITIALIZATION_FAILED;
  Event e("vkCreateImageView", state, {{"createInfo", bits(info)}, {"allocator", bits(allocator)}});
  if (info) e.detail("image_view_info", {{"image", bits(info->image)}, {"pNextPresent", info->pNext ? 1U : 0U},
      {"viewType", bits(info->viewType)}, {"format", bits(info->format)}, {"flags", info->flags},
      {"aspectMask", info->subresourceRange.aspectMask}, {"baseMipLevel", info->subresourceRange.baseMipLevel},
      {"levelCount", info->subresourceRange.levelCount}, {"baseArrayLayer", info->subresourceRange.baseArrayLayer},
      {"layerCount", info->subresourceRange.layerCount}});
  const auto result = fn(device, info, allocator, output);
  e.finish(result, {{"imageView", result == VK_SUCCESS && output ? bits(*output) : 0}}); return result;
}
VKAPI_ATTR void VKAPI_CALL vkDestroyImageView(VkDevice device, VkImageView view, const VkAllocationCallbacks* allocator) {
  auto state = deviceFor(device); auto fn = next<PFN_vkDestroyImageView>(state, "vkDestroyImageView"); if (!fn) return;
  Event e("vkDestroyImageView", state, {{"imageView", bits(view)}, {"allocator", bits(allocator)}});
  fn(device, view, allocator); e.finish(VK_SUCCESS);
}
VKAPI_ATTR VkResult VKAPI_CALL vkAllocateMemory(VkDevice device, const VkMemoryAllocateInfo* info,
    const VkAllocationCallbacks* allocator, VkDeviceMemory* output) {
  auto state = deviceFor(device); auto fn = next<PFN_vkAllocateMemory>(state, "vkAllocateMemory");
  if (!fn) return VK_ERROR_INITIALIZATION_FAILED;
  Event e("vkAllocateMemory", state, {{"allocateInfo", bits(info)}, {"allocator", bits(allocator)}});
  if (info) { e.detail("memory_info", {{"pNextPresent", info->pNext ? 1U : 0U}, {"allocationSize", info->allocationSize}, {"memoryTypeIndex", info->memoryTypeIndex}});
    allocationChain(e, info->pNext); }
  const auto result = fn(device, info, allocator, output);
  e.finish(result, {{"memory", result == VK_SUCCESS && output ? bits(*output) : 0}}); return result;
}
VKAPI_ATTR void VKAPI_CALL vkFreeMemory(VkDevice device, VkDeviceMemory memory, const VkAllocationCallbacks* allocator) {
  auto state = deviceFor(device); auto fn = next<PFN_vkFreeMemory>(state, "vkFreeMemory"); if (!fn) return;
  Event e("vkFreeMemory", state, {{"memory", bits(memory)}, {"allocator", bits(allocator)}, {"nextFunction", bits(fn)}});
  fn(device, memory, allocator); e.finish(VK_SUCCESS);
}
VKAPI_ATTR VkResult VKAPI_CALL vkBindImageMemory(VkDevice device, VkImage image, VkDeviceMemory memory, VkDeviceSize offset) {
  auto state = deviceFor(device); auto fn = next<PFN_vkBindImageMemory>(state, "vkBindImageMemory");
  if (!fn) return VK_ERROR_INITIALIZATION_FAILED;
  Event e("vkBindImageMemory", state, {{"image", bits(image)}, {"memory", bits(memory)}, {"memoryOffset", offset}});
  const auto result = fn(device, image, memory, offset); e.finish(result); return result;
}
#define RQVP_BIND2(NAME) \
VKAPI_ATTR VkResult VKAPI_CALL NAME(VkDevice device, uint32_t count, const VkBindImageMemoryInfo* infos) { \
  auto state = deviceFor(device); auto fn = next<PFN_##NAME>(state, #NAME); if (!fn) return VK_ERROR_INITIALIZATION_FAILED; \
  Event e(#NAME, state, {{"bindInfoCount", count}, {"bindInfos", bits(infos)}}); \
  if (infos) for (uint32_t n = 0; n < std::min(count, kMaxDetails); ++n) e.detail("image_memory_bind", \
      {{"bindIndex", n}, {"image", bits(infos[n].image)}, {"memory", bits(infos[n].memory)}, \
       {"memoryOffset", infos[n].memoryOffset}, {"pNextPresent", infos[n].pNext ? 1U : 0U}}); \
  if (infos) for (uint32_t n = 0; n < std::min(count, kMaxDetails); ++n) bindingChain(e, n, infos[n].pNext); \
  if (count > kMaxDetails) e.gap(2); \
  auto result = fn(device, count, infos); e.finish(result); return result; \
}
RQVP_BIND2(vkBindImageMemory2)
RQVP_BIND2(vkBindImageMemory2KHR)
#undef RQVP_BIND2

VKAPI_ATTR void VKAPI_CALL vkUpdateDescriptorSets(VkDevice device, uint32_t writeCount,
    const VkWriteDescriptorSet* writes, uint32_t copyCount, const VkCopyDescriptorSet* copies) {
  auto state = deviceFor(device); auto fn = next<PFN_vkUpdateDescriptorSets>(state, "vkUpdateDescriptorSets"); if (!fn) return;
  Event e("vkUpdateDescriptorSets", state, {{"writeCount", writeCount}, {"writes", bits(writes)}, {"copyCount", copyCount}, {"copies", bits(copies)}, {"nextFunction", bits(fn)}});
  uint32_t remaining = kMaxDetails;
  if (writes) for (uint32_t n = 0; n < std::min(writeCount, kMaxDetails); ++n) {
    const auto& w = writes[n];
    e.detail("descriptor_write", {{"writeIndex", n}, {"dstSet", bits(w.dstSet)}, {"dstBinding", w.dstBinding},
        {"dstArrayElement", w.dstArrayElement}, {"descriptorType", bits(w.descriptorType)},
        {"descriptorCount", w.descriptorCount}, {"pNextPresent", w.pNext ? 1U : 0U}});
    const bool sampler = w.descriptorType == VK_DESCRIPTOR_TYPE_SAMPLER || w.descriptorType == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    const bool image = sampler || w.descriptorType == VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE ||
        w.descriptorType == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE || w.descriptorType == VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT;
    if (image && w.pImageInfo) {
      const uint32_t amount = std::min(w.descriptorCount, remaining); remaining -= amount;
      for (uint32_t j = 0; j < amount; ++j) {
        const auto& i = w.pImageInfo[j];
        e.detail("descriptor_image", {{"writeIndex", n}, {"imageIndex", j}, {"dstSet", bits(w.dstSet)},
            {"dstBinding", w.dstBinding}, {"dstArrayElement", w.dstArrayElement}, {"descriptorType", bits(w.descriptorType)},
            {"imageView", w.descriptorType != VK_DESCRIPTOR_TYPE_SAMPLER ? bits(i.imageView) : 0},
            {"imageLayout", w.descriptorType != VK_DESCRIPTOR_TYPE_SAMPLER ? bits(i.imageLayout) : 0}, {"sampler", sampler ? bits(i.sampler) : 0}});
      }
      if (amount != w.descriptorCount) e.gap(2);
    } else if (w.descriptorType != VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER && w.descriptorType != VK_DESCRIPTOR_TYPE_STORAGE_BUFFER &&
        w.descriptorType != VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC && w.descriptorType != VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC &&
        w.descriptorType != VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER && w.descriptorType != VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER) e.gap(3);
    if (w.pNext) e.gap(4); // unparsed extension descriptor encoding
  }
  if (copies) for (uint32_t n = 0; n < std::min(copyCount, kMaxDetails); ++n) {
    const auto& c = copies[n];
    e.detail("descriptor_copy", {{"copyIndex", n}, {"srcSet", bits(c.srcSet)}, {"srcBinding", c.srcBinding},
        {"srcArrayElement", c.srcArrayElement}, {"dstSet", bits(c.dstSet)}, {"dstBinding", c.dstBinding},
        {"dstArrayElement", c.dstArrayElement}, {"descriptorCount", c.descriptorCount}, {"pNextPresent", c.pNext ? 1U : 0U}});
    if (c.pNext) e.gap(4);
  }
  if (writeCount > kMaxDetails || copyCount > kMaxDetails) e.gap(2);
  fn(device, writeCount, writes, copyCount, copies); e.finish(VK_SUCCESS);
}

VKAPI_ATTR VkResult VKAPI_CALL vkCreateSwapchainKHR(VkDevice device, const VkSwapchainCreateInfoKHR* info,
    const VkAllocationCallbacks* allocator, VkSwapchainKHR* output) {
  auto state = deviceFor(device); auto fn = next<PFN_vkCreateSwapchainKHR>(state, "vkCreateSwapchainKHR");
  if (!fn) return VK_ERROR_INITIALIZATION_FAILED;
  Event e("vkCreateSwapchainKHR", state, {{"createInfo", bits(info)}, {"allocator", bits(allocator)}});
  if (info) e.detail("swapchain_info", {{"pNextPresent", info->pNext ? 1U : 0U}, {"surface", bits(info->surface)},
      {"oldSwapchain", bits(info->oldSwapchain)}, {"minImageCount", info->minImageCount}, {"imageFormat", bits(info->imageFormat)},
      {"imageColorSpace", bits(info->imageColorSpace)}, {"width", info->imageExtent.width}, {"height", info->imageExtent.height},
      {"imageArrayLayers", info->imageArrayLayers}, {"imageUsage", info->imageUsage}, {"presentMode", bits(info->presentMode)}});
  const auto result = fn(device, info, allocator, output);
  e.finish(result, {{"swapchain", result == VK_SUCCESS && output ? bits(*output) : 0}}); return result;
}
VKAPI_ATTR VkResult VKAPI_CALL vkGetSwapchainImagesKHR(VkDevice device, VkSwapchainKHR swapchain,
    uint32_t* count, VkImage* images) {
  auto state = deviceFor(device); auto fn = next<PFN_vkGetSwapchainImagesKHR>(state, "vkGetSwapchainImagesKHR");
  if (!fn) return VK_ERROR_INITIALIZATION_FAILED;
  const uint32_t capacity = count && images ? *count : 0;
  Event e("vkGetSwapchainImagesKHR", state, {{"swapchain", bits(swapchain)}, {"capacity", capacity}, {"images", bits(images)}});
  const auto result = fn(device, swapchain, count, images);
  if ((result == VK_SUCCESS || result == VK_INCOMPLETE) && count && images) {
    const uint32_t amount = std::min({capacity, *count, kMaxDetails});
    for (uint32_t n = 0; n < amount; ++n) e.detail("swapchain_image", {{"swapchain", bits(swapchain)}, {"imageIndex", n}, {"image", bits(images[n])}});
    if (*count > capacity || amount != *count) e.gap(2);
  }
  e.finish(result, {{"imageCount", count && (result == VK_SUCCESS || result == VK_INCOMPLETE) ? *count : 0}}); return result;
}
VKAPI_ATTR void VKAPI_CALL vkDestroySwapchainKHR(VkDevice device, VkSwapchainKHR swapchain, const VkAllocationCallbacks* allocator) {
  auto state = deviceFor(device); auto fn = next<PFN_vkDestroySwapchainKHR>(state, "vkDestroySwapchainKHR"); if (!fn) return;
  Event e("vkDestroySwapchainKHR", state, {{"swapchain", bits(swapchain)}, {"allocator", bits(allocator)}});
  fn(device, swapchain, allocator); e.finish(VK_SUCCESS);
}
VKAPI_ATTR VkResult VKAPI_CALL vkSetDebugUtilsObjectNameEXT(VkDevice device, const VkDebugUtilsObjectNameInfoEXT* info) {
  auto state = deviceFor(device); auto fn = next<PFN_vkSetDebugUtilsObjectNameEXT>(state, "vkSetDebugUtilsObjectNameEXT");
  if (!fn) return VK_ERROR_INITIALIZATION_FAILED;
  Event e("vkSetDebugUtilsObjectNameEXT", state);
  if (info) names(e, info->objectType, info->objectHandle, info->pObjectName);
  const auto result = fn(device, info); e.finish(result); return result;
}
VKAPI_ATTR VkResult VKAPI_CALL vkDebugMarkerSetObjectNameEXT(VkDevice device, const VkDebugMarkerObjectNameInfoEXT* info) {
  auto state = deviceFor(device); auto fn = next<PFN_vkDebugMarkerSetObjectNameEXT>(state, "vkDebugMarkerSetObjectNameEXT");
  if (!fn) return VK_ERROR_INITIALIZATION_FAILED;
  Event e("vkDebugMarkerSetObjectNameEXT", state);
  if (info) e.detail("debug_marker_name_type", {{"debugReportObjectType", bits(info->objectType)}});
  if (info) names(e, VK_OBJECT_TYPE_UNKNOWN, info->object, info->pObjectName);
  const auto result = fn(device, info); e.finish(result); return result;
}

// No template or push decoding is claimed. Keep tracing, emit a coverage gap,
// and forward the untouched command through the exact supported alias.
#define RQVP_TEMPLATE(NAME) \
VKAPI_ATTR void VKAPI_CALL NAME(VkDevice device, VkDescriptorSet set, VkDescriptorUpdateTemplate update, const void* data) { \
  auto state = deviceFor(device); auto fn = next<PFN_##NAME>(state, #NAME); if (!fn) return; \
  Event e(#NAME, state, {{"dstSet", bits(set)}, {"descriptorUpdateTemplate", bits(update)}}); \
  e.detail("coverage_gap", {{"functionHashFNV64", hashBytes(#NAME, sizeof(#NAME)-1)}, {"gapReason", 5}}); \
  fn(device, set, update, data); e.finish(VK_SUCCESS); \
}
RQVP_TEMPLATE(vkUpdateDescriptorSetWithTemplate)
RQVP_TEMPLATE(vkUpdateDescriptorSetWithTemplateKHR)
#undef RQVP_TEMPLATE
VKAPI_ATTR void VKAPI_CALL vkCmdPushDescriptorSetKHR(VkCommandBuffer command, VkPipelineBindPoint bindPoint,
    VkPipelineLayout layout, uint32_t set, uint32_t count, const VkWriteDescriptorSet* writes) {
  auto state = deviceFor(command); auto fn = next<PFN_vkCmdPushDescriptorSetKHR>(state, "vkCmdPushDescriptorSetKHR"); if (!fn) return;
  Event e("vkCmdPushDescriptorSetKHR", state, {{"commandBuffer", bits(command)}}); e.gap(5);
  fn(command, bindPoint, layout, set, count, writes); e.finish(VK_SUCCESS);
}
VKAPI_ATTR void VKAPI_CALL vkCmdPushDescriptorSetWithTemplateKHR(VkCommandBuffer command, VkDescriptorUpdateTemplate update,
    VkPipelineLayout layout, uint32_t set, const void* data) {
  auto state = deviceFor(command); auto fn = next<PFN_vkCmdPushDescriptorSetWithTemplateKHR>(state, "vkCmdPushDescriptorSetWithTemplateKHR"); if (!fn) return;
  Event e("vkCmdPushDescriptorSetWithTemplateKHR", state, {{"commandBuffer", bits(command)}}); e.gap(5);
  fn(command, update, layout, set, data); e.finish(VK_SUCCESS);
}
VKAPI_ATTR void VKAPI_CALL vkCmdPushDescriptorSet2KHR(VkCommandBuffer command, const VkPushDescriptorSetInfoKHR* info) {
  auto state = deviceFor(command); auto fn = next<PFN_vkCmdPushDescriptorSet2KHR>(state, "vkCmdPushDescriptorSet2KHR"); if (!fn) return;
  Event e("vkCmdPushDescriptorSet2KHR", state, {{"commandBuffer", bits(command)}}); e.gap(5);
  fn(command, info); e.finish(VK_SUCCESS);
}
VKAPI_ATTR void VKAPI_CALL vkCmdPushDescriptorSetWithTemplate2KHR(VkCommandBuffer command, const VkPushDescriptorSetWithTemplateInfoKHR* info) {
  auto state = deviceFor(command); auto fn = next<PFN_vkCmdPushDescriptorSetWithTemplate2KHR>(state, "vkCmdPushDescriptorSetWithTemplate2KHR"); if (!fn) return;
  Event e("vkCmdPushDescriptorSetWithTemplate2KHR", state, {{"commandBuffer", bits(command)}}); e.gap(5);
  fn(command, info); e.finish(VK_SUCCESS);
}

VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateInstanceLayerProperties(uint32_t* count, VkLayerProperties* properties) {
  if (!count) return VK_ERROR_INITIALIZATION_FAILED;
  if (!properties) { *count = 1; return VK_SUCCESS; }
  if (!*count) return VK_INCOMPLETE;
  VkLayerProperties own{};
  std::strncpy(own.layerName, kLayerName, sizeof(own.layerName)-1);
  own.specVersion = VK_API_VERSION_1_3; own.implementationVersion = 1;
  std::strncpy(own.description, "Optional debug Vulkan provenance; participating chains only", sizeof(own.description)-1);
  properties[0] = own; *count = 1; return VK_SUCCESS;
}
VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateDeviceLayerProperties(VkPhysicalDevice, uint32_t* count, VkLayerProperties* properties) {
  return vkEnumerateInstanceLayerProperties(count, properties);
}
VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateInstanceExtensionProperties(const char* name, uint32_t* count, VkExtensionProperties* properties) {
  if (name && std::strcmp(name, kLayerName) == 0) { if (!count) return VK_ERROR_INITIALIZATION_FAILED; *count = 0; return VK_SUCCESS; }
  auto gipa = globalGipa.load();
  auto fn = gipa ? reinterpret_cast<PFN_vkEnumerateInstanceExtensionProperties>(gipa(VK_NULL_HANDLE, "vkEnumerateInstanceExtensionProperties")) : nullptr;
  return fn ? fn(name, count, properties) : VK_ERROR_LAYER_NOT_PRESENT;
}
VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateDeviceExtensionProperties(VkPhysicalDevice physical, const char* name,
    uint32_t* count, VkExtensionProperties* properties) {
  if (name && std::strcmp(name, kLayerName) == 0) { if (!count) return VK_ERROR_INITIALIZATION_FAILED; *count = 0; return VK_SUCCESS; }
  auto state = instanceFor(physical);
  auto fn = state ? reinterpret_cast<PFN_vkEnumerateDeviceExtensionProperties>(state->gipa(state->handle, "vkEnumerateDeviceExtensionProperties")) : nullptr;
  return fn ? fn(physical, name, count, properties) : VK_ERROR_LAYER_NOT_PRESENT;
}

// Query each exact next pointer BEFORE offering an interception; no synthetic
// core/KHR alias support, and all unknown supported functions pass through.
PFN_vkVoidFunction deviceIntercept(const char* name) {
#define RQVP_INTERCEPT(NAME) if (std::strcmp(name, #NAME) == 0) return reinterpret_cast<PFN_vkVoidFunction>(NAME)
  RQVP_INTERCEPT(vkGetDeviceProcAddr); RQVP_INTERCEPT(vkDestroyDevice);
  RQVP_INTERCEPT(vkCreateImage); RQVP_INTERCEPT(vkDestroyImage);
  RQVP_INTERCEPT(vkCreateImageView); RQVP_INTERCEPT(vkDestroyImageView);
  RQVP_INTERCEPT(vkAllocateMemory); RQVP_INTERCEPT(vkFreeMemory);
  RQVP_INTERCEPT(vkBindImageMemory); RQVP_INTERCEPT(vkBindImageMemory2); RQVP_INTERCEPT(vkBindImageMemory2KHR);
  RQVP_INTERCEPT(vkUpdateDescriptorSets); RQVP_INTERCEPT(vkUpdateDescriptorSetWithTemplate); RQVP_INTERCEPT(vkUpdateDescriptorSetWithTemplateKHR);
  RQVP_INTERCEPT(vkCmdPushDescriptorSetKHR); RQVP_INTERCEPT(vkCmdPushDescriptorSetWithTemplateKHR);
  RQVP_INTERCEPT(vkCmdPushDescriptorSet2KHR); RQVP_INTERCEPT(vkCmdPushDescriptorSetWithTemplate2KHR);
  RQVP_INTERCEPT(vkCreateSwapchainKHR); RQVP_INTERCEPT(vkGetSwapchainImagesKHR); RQVP_INTERCEPT(vkDestroySwapchainKHR);
  RQVP_INTERCEPT(vkSetDebugUtilsObjectNameEXT); RQVP_INTERCEPT(vkDebugMarkerSetObjectNameEXT);
#undef RQVP_INTERCEPT
  return nullptr;
}
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice device, const char* name) {
  if (!name) return nullptr;
  auto state = deviceFor(device); if (!state) return nullptr;
  auto original = state->gdpa(device, name); if (!original) return nullptr;
  auto intercept = deviceIntercept(name); return intercept ? intercept : original;
}
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance, const char* name) {
  if (!name) return nullptr;
  if (std::strcmp(name, "vkGetInstanceProcAddr") == 0) return reinterpret_cast<PFN_vkVoidFunction>(vkGetInstanceProcAddr);
  if (std::strcmp(name, "vk_layerGetPhysicalDeviceProcAddr") == 0) return reinterpret_cast<PFN_vkVoidFunction>(vk_layerGetPhysicalDeviceProcAddr);
  if (!instance) {
    if (std::strcmp(name, "vkCreateInstance") == 0) return reinterpret_cast<PFN_vkVoidFunction>(vkCreateInstance);
    if (std::strcmp(name, "vkEnumerateInstanceLayerProperties") == 0) return reinterpret_cast<PFN_vkVoidFunction>(vkEnumerateInstanceLayerProperties);
    if (std::strcmp(name, "vkEnumerateInstanceExtensionProperties") == 0) return reinterpret_cast<PFN_vkVoidFunction>(vkEnumerateInstanceExtensionProperties);
    auto gipa = globalGipa.load(); return gipa ? gipa(instance, name) : nullptr;
  }
  auto state = instanceFor(instance); if (!state) return nullptr;
  auto original = state->gipa(instance, name); if (!original) return nullptr;
  if (std::strcmp(name, "vkCreateDevice") == 0) return reinterpret_cast<PFN_vkVoidFunction>(vkCreateDevice);
  if (std::strcmp(name, "vkDestroyInstance") == 0) return reinterpret_cast<PFN_vkVoidFunction>(vkDestroyInstance);
  auto intercept = deviceIntercept(name); return intercept ? intercept : original;
}
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vk_layerGetPhysicalDeviceProcAddr(VkInstance instance, const char* name) {
  if (!name) return nullptr;
  if (deviceIntercept(name) || std::strcmp(name, "vkGetInstanceProcAddr") == 0 || std::strcmp(name, "vkCreateInstance") == 0 ||
      std::strcmp(name, "vkDestroyInstance") == 0 || std::strcmp(name, "vkCreateDevice") == 0 ||
      std::strcmp(name, "vkEnumerateInstanceLayerProperties") == 0 || std::strcmp(name, "vkEnumerateInstanceExtensionProperties") == 0)
    return nullptr;
  auto state = instanceFor(instance);
  return state && state->physical ? state->physical(instance, name) : nullptr;
}
VKAPI_ATTR VkResult VKAPI_CALL vkNegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface* version) {
  if (!version || version->sType != LAYER_NEGOTIATE_INTERFACE_STRUCT || version->loaderLayerInterfaceVersion < 1)
    return VK_ERROR_INITIALIZATION_FAILED;
  version->loaderLayerInterfaceVersion = std::min(version->loaderLayerInterfaceVersion, 2U);
  version->pfnGetInstanceProcAddr = vkGetInstanceProcAddr;
  version->pfnGetDeviceProcAddr = vkGetDeviceProcAddr;
  if (version->loaderLayerInterfaceVersion >= 2) version->pfnGetPhysicalDeviceProcAddr = vk_layerGetPhysicalDeviceProcAddr;
  return VK_SUCCESS;
}
} // extern C

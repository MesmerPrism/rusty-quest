#include "spatial_depth_ring_vulkan.hpp"

#include "spatial_depth_ring_spirv.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <time.h>

namespace {

constexpr VkFormat kDepthFormat = VK_FORMAT_D16_UNORM;
constexpr VkFormat kConsumerFormat = VK_FORMAT_R8G8B8A8_UNORM;

uint64_t monotonicNanoseconds() {
  timespec value{};
  clock_gettime(CLOCK_MONOTONIC, &value);
  return static_cast<uint64_t>(value.tv_sec) * 1000000000ULL +
         static_cast<uint64_t>(value.tv_nsec);
}

struct EyePushConstant {
  uint32_t eye;
};

uint64_t opaqueDeviceToken(
    VkDevice device, uint32_t queueFamilyIndex, uint64_t sessionGeneration) {
  uint64_t value = static_cast<uint64_t>(reinterpret_cast<uintptr_t>(device)) ^
                   (static_cast<uint64_t>(queueFamilyIndex) << 32U) ^
                   sessionGeneration;
  value ^= value >> 30U;
  value *= 0xbf58476d1ce4e5b9ULL;
  value ^= value >> 27U;
  value *= 0x94d049bb133111ebULL;
  value ^= value >> 31U;
  return value == 0 ? sessionGeneration : value;
}

}  // namespace

bool DepthGpuHandoffVulkan::bind(
    const VulkanBindingSnapshot& binding, uint64_t sessionGeneration) {
  resetSession();
  if (binding.instance == VK_NULL_HANDLE || binding.physicalDevice == VK_NULL_HANDLE ||
      binding.device == VK_NULL_HANDLE) {
    return false;
  }
  binding_ = binding;
  sessionGeneration_ = sessionGeneration;
  deviceToken_ = opaqueDeviceToken(
      binding_.device, binding_.queueFamilyIndex, sessionGeneration_);
  vkGetDeviceQueue(
      binding_.device, binding_.queueFamilyIndex, binding_.queueIndex, &queue_);
  if (queue_ == VK_NULL_HANDLE) {
    binding_ = {};
    return false;
  }
  return true;
}

bool DepthGpuHandoffVulkan::isBound() const {
  return binding_.device != VK_NULL_HANDLE && queue_ != VK_NULL_HANDLE;
}

bool DepthGpuHandoffVulkan::isConfigured() const {
  return copyPipeline_ != VK_NULL_HANDLE && consumerPipeline_ != VK_NULL_HANDLE &&
         commandPool_ != VK_NULL_HANDLE && !sourceViews_.empty() &&
         slots_[0].depthImage != VK_NULL_HANDLE && slots_[0].consumerImage != VK_NULL_HANDLE;
}

bool DepthGpuHandoffVulkan::findMemoryType(
    uint32_t typeBits,
    VkMemoryPropertyFlags required,
    VkMemoryPropertyFlags preferred,
    uint32_t* memoryTypeIndex,
    bool* coherent) const {
  if (memoryTypeIndex == nullptr || binding_.physicalDevice == VK_NULL_HANDLE) {
    return false;
  }
  VkPhysicalDeviceMemoryProperties properties{};
  vkGetPhysicalDeviceMemoryProperties(binding_.physicalDevice, &properties);
  for (uint32_t pass = 0; pass < 2; ++pass) {
    for (uint32_t index = 0; index < properties.memoryTypeCount; ++index) {
      if ((typeBits & (1U << index)) == 0) {
        continue;
      }
      const VkMemoryPropertyFlags flags = properties.memoryTypes[index].propertyFlags;
      if ((flags & required) != required) {
        continue;
      }
      if (pass == 0 && (flags & preferred) != preferred) {
        continue;
      }
      *memoryTypeIndex = index;
      if (coherent != nullptr) {
        *coherent = (flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
      }
      return true;
    }
  }
  return false;
}

VkResult DepthGpuHandoffVulkan::createImage(
    VkFormat format,
    uint32_t width,
    uint32_t height,
    uint32_t arrayLayers,
    VkImageUsageFlags usage,
    VkImage* image,
    VkDeviceMemory* memory) {
  const VkImageCreateInfo imageInfo{
      VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
      nullptr,
      0,
      VK_IMAGE_TYPE_2D,
      format,
      {width, height, 1},
      1,
      arrayLayers,
      VK_SAMPLE_COUNT_1_BIT,
      VK_IMAGE_TILING_OPTIMAL,
      usage,
      VK_SHARING_MODE_EXCLUSIVE,
      0,
      nullptr,
      VK_IMAGE_LAYOUT_UNDEFINED};
  VkResult result = vkCreateImage(binding_.device, &imageInfo, nullptr, image);
  if (result != VK_SUCCESS) {
    return result;
  }
  VkMemoryRequirements requirements{};
  vkGetImageMemoryRequirements(binding_.device, *image, &requirements);
  uint32_t memoryTypeIndex = 0;
  if (!findMemoryType(
          requirements.memoryTypeBits,
          VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
          VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
          &memoryTypeIndex)) {
    return VK_ERROR_FEATURE_NOT_PRESENT;
  }
  const VkMemoryAllocateInfo allocationInfo{
      VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, nullptr, requirements.size, memoryTypeIndex};
  result = vkAllocateMemory(binding_.device, &allocationInfo, nullptr, memory);
  if (result != VK_SUCCESS) {
    return result;
  }
  return vkBindImageMemory(binding_.device, *image, *memory, 0);
}

VkResult DepthGpuHandoffVulkan::createShaderModule(
    const uint32_t* words, size_t byteSize, VkShaderModule* shaderModule) const {
  const VkShaderModuleCreateInfo createInfo{
      VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, nullptr, 0, byteSize, words};
  return vkCreateShaderModule(binding_.device, &createInfo, nullptr, shaderModule);
}

DepthGpuConfigureOutcome DepthGpuHandoffVulkan::configure(
    uint32_t width, uint32_t height, const std::vector<VkImage>& sourceImages) {
  DepthGpuConfigureOutcome outcome{};
  if (hasPinnedLease()) {
    outcome.stage = "pinned-lease-await-release";
    outcome.result = VK_NOT_READY;
    return outcome;
  }
  destroyCopyResources();
  auto fail = [this, &outcome](const char* stage, VkResult result) {
    outcome.stage = stage;
    outcome.result = result;
    destroyCopyResources();
    return outcome;
  };
  if (!isBound() || width == 0 || height == 0 || sourceImages.empty()) {
    return fail("invalid-input", VK_ERROR_INITIALIZATION_FAILED);
  }

  VkFormatProperties depthProperties{};
  VkFormatProperties consumerProperties{};
  vkGetPhysicalDeviceFormatProperties(binding_.physicalDevice, kDepthFormat, &depthProperties);
  vkGetPhysicalDeviceFormatProperties(binding_.physicalDevice, kConsumerFormat, &consumerProperties);
  const VkFormatFeatureFlags requiredDepthFeatures =
      VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT;
  const VkFormatFeatureFlags requiredConsumerFeatures =
      VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT | VK_FORMAT_FEATURE_TRANSFER_SRC_BIT;
  outcome.deviceLocalD16Supported =
      (depthProperties.optimalTilingFeatures & requiredDepthFeatures) == requiredDepthFeatures;
  if (!outcome.deviceLocalD16Supported ||
      (consumerProperties.optimalTilingFeatures & requiredConsumerFeatures) !=
          requiredConsumerFeatures) {
    return fail("required-format-features", VK_ERROR_FORMAT_NOT_SUPPORTED);
  }

  VkPhysicalDeviceProperties physicalProperties{};
  vkGetPhysicalDeviceProperties(binding_.physicalDevice, &physicalProperties);
  uint32_t queueFamilyCount = 0;
  vkGetPhysicalDeviceQueueFamilyProperties(
      binding_.physicalDevice, &queueFamilyCount, nullptr);
  std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
  vkGetPhysicalDeviceQueueFamilyProperties(
      binding_.physicalDevice, &queueFamilyCount, queueFamilies.data());
  if (binding_.queueFamilyIndex < queueFamilies.size()) {
    timestampValidBits_ = queueFamilies[binding_.queueFamilyIndex].timestampValidBits;
  }
  timestampPeriodNanoseconds_ = physicalProperties.limits.timestampPeriod;
  timestampSupported_ = timestampValidBits_ > 0 && timestampPeriodNanoseconds_ > 0.0f;
  outcome.timestampSupported = timestampSupported_;
  outcome.timestampValidBits = timestampValidBits_;
  outcome.timestampPeriodNanoseconds = timestampPeriodNanoseconds_;
  outcome.ahardwareBufferFunctionsCallable =
      vkGetDeviceProcAddr(binding_.device, "vkGetMemoryAndroidHardwareBufferANDROID") != nullptr &&
      vkGetDeviceProcAddr(binding_.device, "vkGetAndroidHardwareBufferPropertiesANDROID") != nullptr;
  outcome.externalSemaphoreFdFunctionsCallable =
      vkGetDeviceProcAddr(binding_.device, "vkGetSemaphoreFdKHR") != nullptr &&
      vkGetDeviceProcAddr(binding_.device, "vkImportSemaphoreFdKHR") != nullptr;

  width_ = width;
  height_ = height;
  outputWidth_ = width_ * 2U;
  outputHeight_ = height_;

  const VkSamplerCreateInfo samplerInfo{
      VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
      nullptr,
      0,
      VK_FILTER_NEAREST,
      VK_FILTER_NEAREST,
      VK_SAMPLER_MIPMAP_MODE_NEAREST,
      VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
      VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
      VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
      0.0f,
      VK_FALSE,
      1.0f,
      VK_FALSE,
      VK_COMPARE_OP_NEVER,
      0.0f,
      0.0f,
      VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK,
      VK_FALSE};
  VkResult result = vkCreateSampler(binding_.device, &samplerInfo, nullptr, &sampler_);
  if (result != VK_SUCCESS) {
    return fail("create-sampler", result);
  }

  const VkDescriptorSetLayoutBinding sampledBinding{
      0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1,
      VK_SHADER_STAGE_FRAGMENT_BIT, nullptr};
  const VkDescriptorSetLayoutCreateInfo descriptorLayoutInfo{
      VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
      nullptr,
      0,
      1,
      &sampledBinding};
  result = vkCreateDescriptorSetLayout(
      binding_.device, &descriptorLayoutInfo, nullptr, &copyDescriptorSetLayout_);
  if (result != VK_SUCCESS) {
    return fail("create-copy-descriptor-layout", result);
  }
  result = vkCreateDescriptorSetLayout(
      binding_.device, &descriptorLayoutInfo, nullptr, &consumerDescriptorSetLayout_);
  if (result != VK_SUCCESS) {
    return fail("create-consumer-descriptor-layout", result);
  }

  const VkPushConstantRange pushConstantRange{
      VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(EyePushConstant)};
  const VkPipelineLayoutCreateInfo copyPipelineLayoutInfo{
      VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
      nullptr,
      0,
      1,
      &copyDescriptorSetLayout_,
      1,
      &pushConstantRange};
  result = vkCreatePipelineLayout(
      binding_.device, &copyPipelineLayoutInfo, nullptr, &copyPipelineLayout_);
  if (result != VK_SUCCESS) {
    return fail("create-copy-pipeline-layout", result);
  }
  const VkPipelineLayoutCreateInfo consumerPipelineLayoutInfo{
      VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
      nullptr,
      0,
      1,
      &consumerDescriptorSetLayout_,
      1,
      &pushConstantRange};
  result = vkCreatePipelineLayout(
      binding_.device, &consumerPipelineLayoutInfo, nullptr, &consumerPipelineLayout_);
  if (result != VK_SUCCESS) {
    return fail("create-consumer-pipeline-layout", result);
  }

  const VkAttachmentDescription copyAttachment{
      0,
      kDepthFormat,
      VK_SAMPLE_COUNT_1_BIT,
      VK_ATTACHMENT_LOAD_OP_DONT_CARE,
      VK_ATTACHMENT_STORE_OP_STORE,
      VK_ATTACHMENT_LOAD_OP_DONT_CARE,
      VK_ATTACHMENT_STORE_OP_DONT_CARE,
      VK_IMAGE_LAYOUT_UNDEFINED,
      VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
  const VkAttachmentReference copyDepthReference{
      0, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL};
  const VkSubpassDescription copySubpass{
      0,
      VK_PIPELINE_BIND_POINT_GRAPHICS,
      0,
      nullptr,
      0,
      nullptr,
      nullptr,
      &copyDepthReference,
      0,
      nullptr};
  const VkSubpassDependency copyDependencies[] = {
      {VK_SUBPASS_EXTERNAL,
       0,
       VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
       VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
       VK_ACCESS_SHADER_READ_BIT,
       VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
       0},
      {0,
       VK_SUBPASS_EXTERNAL,
       VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
       VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
       VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
       VK_ACCESS_SHADER_READ_BIT,
       0}};
  const VkRenderPassCreateInfo copyRenderPassInfo{
      VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
      nullptr,
      0,
      1,
      &copyAttachment,
      1,
      &copySubpass,
      2,
      copyDependencies};
  result = vkCreateRenderPass(
      binding_.device, &copyRenderPassInfo, nullptr, &copyRenderPass_);
  if (result != VK_SUCCESS) {
    return fail("create-copy-render-pass", result);
  }

  const VkAttachmentDescription consumerAttachment{
      0,
      kConsumerFormat,
      VK_SAMPLE_COUNT_1_BIT,
      VK_ATTACHMENT_LOAD_OP_DONT_CARE,
      VK_ATTACHMENT_STORE_OP_STORE,
      VK_ATTACHMENT_LOAD_OP_DONT_CARE,
      VK_ATTACHMENT_STORE_OP_DONT_CARE,
      VK_IMAGE_LAYOUT_UNDEFINED,
      VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
  const VkAttachmentReference consumerColorReference{
      0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
  const VkSubpassDescription consumerSubpass{
      0,
      VK_PIPELINE_BIND_POINT_GRAPHICS,
      0,
      nullptr,
      1,
      &consumerColorReference,
      nullptr,
      nullptr,
      0,
      nullptr};
  const VkSubpassDependency consumerDependency{
      VK_SUBPASS_EXTERNAL,
      0,
      VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
      VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
      VK_ACCESS_SHADER_READ_BIT,
      VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
      0};
  const VkRenderPassCreateInfo consumerRenderPassInfo{
      VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
      nullptr,
      0,
      1,
      &consumerAttachment,
      1,
      &consumerSubpass,
      1,
      &consumerDependency};
  result = vkCreateRenderPass(
      binding_.device, &consumerRenderPassInfo, nullptr, &consumerRenderPass_);
  if (result != VK_SUCCESS) {
    return fail("create-consumer-render-pass", result);
  }

  result = createShaderModule(
      kDepthGpuVertexSpirv, kDepthGpuVertexSpirvSize, &vertexShaderModule_);
  if (result != VK_SUCCESS) {
    return fail("create-vertex-shader", result);
  }
  result = createShaderModule(
      kDepthGpuCopyFragmentSpirv,
      kDepthGpuCopyFragmentSpirvSize,
      &copyFragmentShaderModule_);
  if (result != VK_SUCCESS) {
    return fail("create-copy-fragment-shader", result);
  }
  result = createShaderModule(
      kDepthGpuConsumerFragmentSpirv,
      kDepthGpuConsumerFragmentSpirvSize,
      &consumerFragmentShaderModule_);
  if (result != VK_SUCCESS) {
    return fail("create-consumer-fragment-shader", result);
  }

  const VkPipelineShaderStageCreateInfo vertexStage{
      VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
      nullptr,
      0,
      VK_SHADER_STAGE_VERTEX_BIT,
      vertexShaderModule_,
      "main",
      nullptr};
  const VkPipelineShaderStageCreateInfo copyFragmentStage{
      VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
      nullptr,
      0,
      VK_SHADER_STAGE_FRAGMENT_BIT,
      copyFragmentShaderModule_,
      "main",
      nullptr};
  const VkPipelineShaderStageCreateInfo consumerFragmentStage{
      VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
      nullptr,
      0,
      VK_SHADER_STAGE_FRAGMENT_BIT,
      consumerFragmentShaderModule_,
      "main",
      nullptr};
  const VkPipelineVertexInputStateCreateInfo vertexInput{
      VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO,
      nullptr,
      0,
      0,
      nullptr,
      0,
      nullptr};
  const VkPipelineInputAssemblyStateCreateInfo inputAssembly{
      VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
      nullptr,
      0,
      VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,
      VK_FALSE};
  const VkPipelineViewportStateCreateInfo viewportState{
      VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
      nullptr,
      0,
      1,
      nullptr,
      1,
      nullptr};
  const VkPipelineRasterizationStateCreateInfo rasterization{
      VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
      nullptr,
      0,
      VK_FALSE,
      VK_FALSE,
      VK_POLYGON_MODE_FILL,
      VK_CULL_MODE_NONE,
      VK_FRONT_FACE_COUNTER_CLOCKWISE,
      VK_FALSE,
      0.0f,
      0.0f,
      0.0f,
      1.0f};
  const VkPipelineMultisampleStateCreateInfo multisample{
      VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
      nullptr,
      0,
      VK_SAMPLE_COUNT_1_BIT,
      VK_FALSE,
      0.0f,
      nullptr,
      VK_FALSE,
      VK_FALSE};
  const VkDynamicState dynamicStates[]{VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
  const VkPipelineDynamicStateCreateInfo dynamicState{
      VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO,
      nullptr,
      0,
      2,
      dynamicStates};
  const VkPipelineDepthStencilStateCreateInfo depthStencil{
      VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO,
      nullptr,
      0,
      VK_TRUE,
      VK_TRUE,
      VK_COMPARE_OP_ALWAYS,
      VK_FALSE,
      VK_FALSE,
      {},
      {},
      0.0f,
      1.0f};
  const VkPipelineColorBlendStateCreateInfo copyColorBlend{
      VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
      nullptr,
      0,
      VK_FALSE,
      VK_LOGIC_OP_COPY,
      0,
      nullptr,
      {0.0f, 0.0f, 0.0f, 0.0f}};
  const VkPipelineShaderStageCreateInfo copyStages[]{vertexStage, copyFragmentStage};
  const VkGraphicsPipelineCreateInfo copyPipelineInfo{
      VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
      nullptr,
      0,
      2,
      copyStages,
      &vertexInput,
      &inputAssembly,
      nullptr,
      &viewportState,
      &rasterization,
      &multisample,
      &depthStencil,
      &copyColorBlend,
      &dynamicState,
      copyPipelineLayout_,
      copyRenderPass_,
      0,
      VK_NULL_HANDLE,
      -1};
  result = vkCreateGraphicsPipelines(
      binding_.device, VK_NULL_HANDLE, 1, &copyPipelineInfo, nullptr, &copyPipeline_);
  if (result != VK_SUCCESS) {
    return fail("create-copy-pipeline", result);
  }

  const VkPipelineColorBlendAttachmentState colorBlendAttachment{
      VK_FALSE,
      VK_BLEND_FACTOR_ONE,
      VK_BLEND_FACTOR_ZERO,
      VK_BLEND_OP_ADD,
      VK_BLEND_FACTOR_ONE,
      VK_BLEND_FACTOR_ZERO,
      VK_BLEND_OP_ADD,
      VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT |
          VK_COLOR_COMPONENT_A_BIT};
  const VkPipelineColorBlendStateCreateInfo consumerColorBlend{
      VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
      nullptr,
      0,
      VK_FALSE,
      VK_LOGIC_OP_COPY,
      1,
      &colorBlendAttachment,
      {0.0f, 0.0f, 0.0f, 0.0f}};
  const VkPipelineShaderStageCreateInfo consumerStages[]{vertexStage, consumerFragmentStage};
  const VkGraphicsPipelineCreateInfo consumerPipelineInfo{
      VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
      nullptr,
      0,
      2,
      consumerStages,
      &vertexInput,
      &inputAssembly,
      nullptr,
      &viewportState,
      &rasterization,
      &multisample,
      nullptr,
      &consumerColorBlend,
      &dynamicState,
      consumerPipelineLayout_,
      consumerRenderPass_,
      0,
      VK_NULL_HANDLE,
      -1};
  result = vkCreateGraphicsPipelines(
      binding_.device,
      VK_NULL_HANDLE,
      1,
      &consumerPipelineInfo,
      nullptr,
      &consumerPipeline_);
  if (result != VK_SUCCESS) {
    return fail("create-consumer-pipeline", result);
  }

  sourceViews_.reserve(sourceImages.size());
  for (VkImage sourceImage : sourceImages) {
    const VkImageViewCreateInfo viewInfo{
        VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        nullptr,
        0,
        sourceImage,
        VK_IMAGE_VIEW_TYPE_2D_ARRAY,
        kDepthFormat,
        {VK_COMPONENT_SWIZZLE_IDENTITY,
         VK_COMPONENT_SWIZZLE_IDENTITY,
         VK_COMPONENT_SWIZZLE_IDENTITY,
         VK_COMPONENT_SWIZZLE_IDENTITY},
        {VK_IMAGE_ASPECT_DEPTH_BIT, 0, 1, 0, kStereoLayerCount}};
    VkImageView view = VK_NULL_HANDLE;
    result = vkCreateImageView(binding_.device, &viewInfo, nullptr, &view);
    if (result != VK_SUCCESS) {
      return fail("create-source-view", result);
    }
    sourceViews_.push_back(view);
  }

  const VkDescriptorPoolSize poolSize{
      VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, kRingSize * 2U};
  const VkDescriptorPoolCreateInfo descriptorPoolInfo{
      VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
      nullptr,
      0,
      kRingSize * 2U,
      1,
      &poolSize};
  result = vkCreateDescriptorPool(
      binding_.device, &descriptorPoolInfo, nullptr, &descriptorPool_);
  if (result != VK_SUCCESS) {
    return fail("create-descriptor-pool", result);
  }
  std::array<VkDescriptorSetLayout, kRingSize * 2U> descriptorLayouts{};
  descriptorLayouts.fill(copyDescriptorSetLayout_);
  for (uint32_t index = kRingSize; index < descriptorLayouts.size(); ++index) {
    descriptorLayouts[index] = consumerDescriptorSetLayout_;
  }
  std::array<VkDescriptorSet, kRingSize * 2U> descriptorSets{};
  const VkDescriptorSetAllocateInfo descriptorAllocateInfo{
      VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
      nullptr,
      descriptorPool_,
      static_cast<uint32_t>(descriptorLayouts.size()),
      descriptorLayouts.data()};
  result = vkAllocateDescriptorSets(
      binding_.device, &descriptorAllocateInfo, descriptorSets.data());
  if (result != VK_SUCCESS) {
    return fail("allocate-descriptor-sets", result);
  }

  const VkCommandPoolCreateInfo commandPoolInfo{
      VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
      nullptr,
      VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
      binding_.queueFamilyIndex};
  result = vkCreateCommandPool(binding_.device, &commandPoolInfo, nullptr, &commandPool_);
  if (result != VK_SUCCESS) {
    return fail("create-command-pool", result);
  }
  std::array<VkCommandBuffer, kRingSize> commandBuffers{};
  const VkCommandBufferAllocateInfo commandBufferInfo{
      VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
      nullptr,
      commandPool_,
      VK_COMMAND_BUFFER_LEVEL_PRIMARY,
      kRingSize};
  result = vkAllocateCommandBuffers(binding_.device, &commandBufferInfo, commandBuffers.data());
  if (result != VK_SUCCESS) {
    return fail("allocate-command-buffers", result);
  }

  for (uint32_t slotIndex = 0; slotIndex < kRingSize; ++slotIndex) {
    Slot& slot = slots_[slotIndex];
    slot.copyDescriptorSet = descriptorSets[slotIndex];
    slot.consumerDescriptorSet = descriptorSets[kRingSize + slotIndex];
    slot.commandBuffer = commandBuffers[slotIndex];

    result = createImage(
        kDepthFormat,
        width_,
        height_,
        kStereoLayerCount,
        VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
        &slot.depthImage,
        &slot.depthMemory);
    if (result != VK_SUCCESS) {
      return fail("create-device-local-depth-image", result);
    }
    const VkImageViewCreateInfo arrayViewInfo{
        VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        nullptr,
        0,
        slot.depthImage,
        VK_IMAGE_VIEW_TYPE_2D_ARRAY,
        kDepthFormat,
        {VK_COMPONENT_SWIZZLE_IDENTITY,
         VK_COMPONENT_SWIZZLE_IDENTITY,
         VK_COMPONENT_SWIZZLE_IDENTITY,
         VK_COMPONENT_SWIZZLE_IDENTITY},
        {VK_IMAGE_ASPECT_DEPTH_BIT, 0, 1, 0, kStereoLayerCount}};
    result = vkCreateImageView(
        binding_.device, &arrayViewInfo, nullptr, &slot.depthArrayView);
    if (result != VK_SUCCESS) {
      return fail("create-device-local-depth-array-view", result);
    }
    for (uint32_t eye = 0; eye < kStereoLayerCount; ++eye) {
      const VkImageViewCreateInfo layerViewInfo{
          VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
          nullptr,
          0,
          slot.depthImage,
          VK_IMAGE_VIEW_TYPE_2D,
          kDepthFormat,
          {VK_COMPONENT_SWIZZLE_IDENTITY,
           VK_COMPONENT_SWIZZLE_IDENTITY,
           VK_COMPONENT_SWIZZLE_IDENTITY,
           VK_COMPONENT_SWIZZLE_IDENTITY},
          {VK_IMAGE_ASPECT_DEPTH_BIT, 0, 1, eye, 1}};
      result = vkCreateImageView(
          binding_.device, &layerViewInfo, nullptr, &slot.depthLayerViews[eye]);
      if (result != VK_SUCCESS) {
        return fail("create-device-local-depth-layer-view", result);
      }
      const VkFramebufferCreateInfo framebufferInfo{
          VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
          nullptr,
          0,
          copyRenderPass_,
          1,
          &slot.depthLayerViews[eye],
          width_,
          height_,
          1};
      result = vkCreateFramebuffer(
          binding_.device, &framebufferInfo, nullptr, &slot.copyFramebuffers[eye]);
      if (result != VK_SUCCESS) {
        return fail("create-copy-framebuffer", result);
      }
    }

    result = createImage(
        kConsumerFormat,
        outputWidth_,
        outputHeight_,
        1,
        VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
            VK_IMAGE_USAGE_SAMPLED_BIT,
        &slot.consumerImage,
        &slot.consumerMemory);
    if (result != VK_SUCCESS) {
      return fail("create-consumer-output-image", result);
    }
    const VkImageViewCreateInfo consumerViewInfo{
        VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        nullptr,
        0,
        slot.consumerImage,
        VK_IMAGE_VIEW_TYPE_2D,
        kConsumerFormat,
        {VK_COMPONENT_SWIZZLE_IDENTITY,
         VK_COMPONENT_SWIZZLE_IDENTITY,
         VK_COMPONENT_SWIZZLE_IDENTITY,
         VK_COMPONENT_SWIZZLE_IDENTITY},
        {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
    result = vkCreateImageView(
        binding_.device, &consumerViewInfo, nullptr, &slot.consumerView);
    if (result != VK_SUCCESS) {
      return fail("create-consumer-output-view", result);
    }
    const VkFramebufferCreateInfo consumerFramebufferInfo{
        VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
        nullptr,
        0,
        consumerRenderPass_,
        1,
        &slot.consumerView,
        outputWidth_,
        outputHeight_,
        1};
    result = vkCreateFramebuffer(
        binding_.device,
        &consumerFramebufferInfo,
        nullptr,
        &slot.consumerFramebuffer);
    if (result != VK_SUCCESS) {
      return fail("create-consumer-framebuffer", result);
    }

    const VkBufferCreateInfo diagnosticBufferInfo{
        VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
        nullptr,
        0,
        kDiagnosticByteSize,
        VK_BUFFER_USAGE_TRANSFER_DST_BIT,
        VK_SHARING_MODE_EXCLUSIVE,
        0,
        nullptr};
    result = vkCreateBuffer(
        binding_.device, &diagnosticBufferInfo, nullptr, &slot.diagnosticBuffer);
    if (result != VK_SUCCESS) {
      return fail("create-bounded-diagnostic-buffer", result);
    }
    VkMemoryRequirements diagnosticRequirements{};
    vkGetBufferMemoryRequirements(
        binding_.device, slot.diagnosticBuffer, &diagnosticRequirements);
    uint32_t diagnosticMemoryType = 0;
    if (!findMemoryType(
            diagnosticRequirements.memoryTypeBits,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
            VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            &diagnosticMemoryType,
            &slot.diagnosticCoherent)) {
      return fail("find-bounded-diagnostic-memory", VK_ERROR_FEATURE_NOT_PRESENT);
    }
    const VkMemoryAllocateInfo diagnosticAllocationInfo{
        VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        nullptr,
        diagnosticRequirements.size,
        diagnosticMemoryType};
    result = vkAllocateMemory(
        binding_.device, &diagnosticAllocationInfo, nullptr, &slot.diagnosticMemory);
    if (result != VK_SUCCESS) {
      return fail("allocate-bounded-diagnostic-memory", result);
    }
    result = vkBindBufferMemory(
        binding_.device, slot.diagnosticBuffer, slot.diagnosticMemory, 0);
    if (result != VK_SUCCESS) {
      return fail("bind-bounded-diagnostic-memory", result);
    }
    result = vkMapMemory(
        binding_.device,
        slot.diagnosticMemory,
        0,
        kDiagnosticByteSize,
        0,
        &slot.diagnosticMapped);
    if (result != VK_SUCCESS) {
      return fail("map-bounded-diagnostic-memory", result);
    }

    const VkDescriptorImageInfo consumerImageDescriptor{
        sampler_, slot.depthArrayView, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    const VkWriteDescriptorSet consumerWrite{
        VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
        nullptr,
        slot.consumerDescriptorSet,
        0,
        0,
        1,
        VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
        &consumerImageDescriptor,
        nullptr,
        nullptr};
    vkUpdateDescriptorSets(binding_.device, 1, &consumerWrite, 0, nullptr);

    const VkFenceCreateInfo fenceInfo{
        VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, nullptr, 0};
    result = vkCreateFence(binding_.device, &fenceInfo, nullptr, &slot.fence);
    if (result != VK_SUCCESS) {
      return fail("create-slot-fence", result);
    }
    if (timestampSupported_) {
      const VkQueryPoolCreateInfo queryPoolInfo{
          VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO,
          nullptr,
          0,
          VK_QUERY_TYPE_TIMESTAMP,
          kTimestampCount,
          0};
      result = vkCreateQueryPool(
          binding_.device, &queryPoolInfo, nullptr, &slot.queryPool);
      if (result != VK_SUCCESS) {
        return fail("create-timestamp-query-pool", result);
      }
    }
  }

  submittedGeneration_ = 0;
  readyGeneration_ = 0;
  nextRingIndex_ = 0;
  readyRingIndex_ = 0;
  fragmentSampleEvidence_ = false;
  diagnosticMinimum_ = 0.0f;
  diagnosticMaximum_ = 0.0f;
  outcome.configured = true;
  outcome.result = VK_SUCCESS;
  outcome.stage = "configured-device-local-d16-fragment-consumer";
  return outcome;
}

uint64_t DepthGpuHandoffVulkan::timestampDeltaNanoseconds(
    uint64_t begin, uint64_t end) const {
  uint64_t mask = std::numeric_limits<uint64_t>::max();
  if (timestampValidBits_ < 64U) {
    mask = (1ULL << timestampValidBits_) - 1ULL;
  }
  const uint64_t ticks = (end - begin) & mask;
  return static_cast<uint64_t>(
      std::llround(static_cast<double>(ticks) * timestampPeriodNanoseconds_));
}

DepthGpuCompletionOutcome DepthGpuHandoffVulkan::pollCompletions(
    uint64_t currentFrameOrdinal) {
  const uint64_t pollStart = monotonicNanoseconds();
  DepthGpuCompletionOutcome outcome{};
  outcome.sampleable = readyGeneration_ > 0;
  outcome.fragmentSampleEvidence = fragmentSampleEvidence_;
  for (uint32_t slotIndex = 0; slotIndex < kRingSize; ++slotIndex) {
    Slot& slot = slots_[slotIndex];
    if (!slot.inFlight) {
      continue;
    }
    const VkResult fenceStatus = vkGetFenceStatus(binding_.device, slot.fence);
    if (fenceStatus == VK_NOT_READY) {
      ++outcome.inFlightCount;
      continue;
    }
    if (fenceStatus != VK_SUCCESS) {
      outcome.result = fenceStatus;
      outcome.stage = "poll-fence-error";
      ++outcome.inFlightCount;
      continue;
    }
    slot.inFlight = false;
    ++outcome.completedCount;
    uint64_t timestamps[kTimestampCount]{};
    uint64_t copyNanoseconds = 0;
    uint64_t consumerNanoseconds = 0;
    uint64_t totalNanoseconds = 0;
    if (timestampSupported_ && slot.queryPool != VK_NULL_HANDLE) {
      const VkResult queryResult = vkGetQueryPoolResults(
          binding_.device,
          slot.queryPool,
          0,
          kTimestampCount,
          sizeof(timestamps),
          timestamps,
          sizeof(uint64_t),
          VK_QUERY_RESULT_64_BIT);
      if (queryResult == VK_SUCCESS) {
        copyNanoseconds = timestampDeltaNanoseconds(timestamps[0], timestamps[1]);
        consumerNanoseconds = timestampDeltaNanoseconds(timestamps[1], timestamps[2]);
        totalNanoseconds = timestampDeltaNanoseconds(timestamps[0], timestamps[3]);
      }
    }

    if (slot.diagnosticRequested && slot.diagnosticMapped != nullptr) {
      if (!slot.diagnosticCoherent) {
        const VkMappedMemoryRange range{
            VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE,
            nullptr,
            slot.diagnosticMemory,
            0,
            VK_WHOLE_SIZE};
        vkInvalidateMappedMemoryRanges(binding_.device, 1, &range);
      }
      const auto* bytes = static_cast<const uint8_t*>(slot.diagnosticMapped);
      bool alphaWritten = true;
      float minimum = 1.0f;
      float maximum = 0.0f;
      for (uint32_t pixel = 0; pixel < 4; ++pixel) {
        const float left = static_cast<float>(bytes[pixel * 4U]) / 255.0f;
        const float right = static_cast<float>(bytes[16U + pixel * 4U + 1U]) / 255.0f;
        minimum = std::min(minimum, std::min(left, right));
        maximum = std::max(maximum, std::max(left, right));
        alphaWritten = alphaWritten && bytes[pixel * 4U + 3U] == 255U &&
                       bytes[16U + pixel * 4U + 3U] == 255U;
      }
      if (alphaWritten && maximum > 0.0f) {
        fragmentSampleEvidence_ = true;
        diagnosticMinimum_ = minimum;
        diagnosticMaximum_ = maximum;
      }
    }

    if (slot.generation > readyGeneration_) {
      readyGeneration_ = slot.generation;
      readyRingIndex_ = slotIndex;
      outcome.completionObserved = true;
      outcome.sampleable = true;
      outcome.generation = slot.generation;
      outcome.frameOrdinal = slot.metadata.frameOrdinal;
      outcome.ringIndex = slotIndex;
      outcome.gpuDepthCopyNanoseconds = copyNanoseconds;
      outcome.gpuFragmentConsumerNanoseconds = consumerNanoseconds;
      outcome.gpuTotalNanoseconds = totalNanoseconds;
      outcome.metadata = slot.metadata;
      outcome.staleFrameCount =
          currentFrameOrdinal > slot.metadata.frameOrdinal
              ? static_cast<uint32_t>(std::min<uint64_t>(
                    currentFrameOrdinal - slot.metadata.frameOrdinal,
                    std::numeric_limits<uint32_t>::max()))
              : 0U;
      outcome.stage = "device-local-ready-fragment-sampled";
      outcome.result = VK_SUCCESS;
    }
  }
  outcome.sampleable = readyGeneration_ > 0;
  outcome.fragmentSampleEvidence = fragmentSampleEvidence_;
  outcome.diagnosticMinimum = diagnosticMinimum_;
  outcome.diagnosticMaximum = diagnosticMaximum_;
  outcome.pollCpuNanoseconds = monotonicNanoseconds() - pollStart;
  return outcome;
}

DepthGpuCompletionOutcome DepthGpuHandoffVulkan::poll(uint64_t currentFrameOrdinal) {
  if (!isConfigured()) {
    DepthGpuCompletionOutcome outcome{};
    outcome.result = VK_ERROR_INITIALIZATION_FAILED;
    outcome.stage = "not-configured";
    return outcome;
  }
  return pollCompletions(currentFrameOrdinal);
}

DepthGpuEnqueueOutcome DepthGpuHandoffVulkan::enqueue(
    uint32_t sourceImageIndex, const DepthGpuFrameMetadata& metadata) {
  const uint64_t enqueueStart = monotonicNanoseconds();
  DepthGpuEnqueueOutcome outcome{};
  outcome.submit.attempted = true;
  if (!isConfigured() || sourceImageIndex >= sourceViews_.size()) {
    outcome.submit.result = VK_ERROR_INITIALIZATION_FAILED;
    outcome.submit.stage = "not-configured";
    return outcome;
  }

  outcome.completion = pollCompletions(metadata.frameOrdinal);
  uint32_t selectedSlot = kRingSize;
  for (uint32_t offset = 0; offset < kRingSize; ++offset) {
    const uint32_t candidate = (nextRingIndex_ + offset) % kRingSize;
    if (!slots_[candidate].inFlight && slots_[candidate].leaseId == 0) {
      selectedSlot = candidate;
      break;
    }
  }
  if (selectedSlot == kRingSize) {
    outcome.submit.dropped = true;
    outcome.submit.result = VK_NOT_READY;
    outcome.submit.stage = "ring-saturated-no-host-wait";
    outcome.submit.enqueueCpuNanoseconds = monotonicNanoseconds() - enqueueStart;
    return outcome;
  }

  Slot& slot = slots_[selectedSlot];
  outcome.submit.ringIndex = selectedSlot;
  VkResult result = vkResetFences(binding_.device, 1, &slot.fence);
  if (result != VK_SUCCESS) {
    outcome.submit.result = result;
    outcome.submit.stage = "reset-slot-fence";
    return outcome;
  }
  result = vkResetCommandBuffer(slot.commandBuffer, 0);
  if (result != VK_SUCCESS) {
    outcome.submit.result = result;
    outcome.submit.stage = "reset-command-buffer";
    return outcome;
  }

  const VkDescriptorImageInfo sourceImageDescriptor{
      sampler_, sourceViews_[sourceImageIndex], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
  const VkWriteDescriptorSet sourceWrite{
      VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
      nullptr,
      slot.copyDescriptorSet,
      0,
      0,
      1,
      VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
      &sourceImageDescriptor,
      nullptr,
      nullptr};
  vkUpdateDescriptorSets(binding_.device, 1, &sourceWrite, 0, nullptr);

  const VkCommandBufferBeginInfo commandBeginInfo{
      VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
      nullptr,
      VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
      nullptr};
  result = vkBeginCommandBuffer(slot.commandBuffer, &commandBeginInfo);
  if (result != VK_SUCCESS) {
    outcome.submit.result = result;
    outcome.submit.stage = "begin-command-buffer";
    return outcome;
  }
  if (timestampSupported_) {
    vkCmdResetQueryPool(slot.commandBuffer, slot.queryPool, 0, kTimestampCount);
    vkCmdWriteTimestamp(
        slot.commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, slot.queryPool, 0);
  }

  const VkViewport copyViewport{
      0.0f, 0.0f, static_cast<float>(width_), static_cast<float>(height_), 0.0f, 1.0f};
  const VkRect2D copyScissor{{0, 0}, {width_, height_}};
  const VkClearValue depthClear{{{1.0f, 0U}}};
  for (uint32_t eye = 0; eye < kStereoLayerCount; ++eye) {
    const VkRenderPassBeginInfo renderPassBegin{
        VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
        nullptr,
        copyRenderPass_,
        slot.copyFramebuffers[eye],
        {{0, 0}, {width_, height_}},
        1,
        &depthClear};
    vkCmdBeginRenderPass(slot.commandBuffer, &renderPassBegin, VK_SUBPASS_CONTENTS_INLINE);
    vkCmdSetViewport(slot.commandBuffer, 0, 1, &copyViewport);
    vkCmdSetScissor(slot.commandBuffer, 0, 1, &copyScissor);
    vkCmdBindPipeline(slot.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, copyPipeline_);
    vkCmdBindDescriptorSets(
        slot.commandBuffer,
        VK_PIPELINE_BIND_POINT_GRAPHICS,
        copyPipelineLayout_,
        0,
        1,
        &slot.copyDescriptorSet,
        0,
        nullptr);
    const EyePushConstant pushConstant{eye};
    vkCmdPushConstants(
        slot.commandBuffer,
        copyPipelineLayout_,
        VK_SHADER_STAGE_FRAGMENT_BIT,
        0,
        sizeof(pushConstant),
        &pushConstant);
    vkCmdDraw(slot.commandBuffer, 3, 1, 0, 0);
    vkCmdEndRenderPass(slot.commandBuffer);
  }
  if (timestampSupported_) {
    vkCmdWriteTimestamp(
        slot.commandBuffer,
        VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
        slot.queryPool,
        1);
  }

  const VkImageMemoryBarrier depthReadyBarrier{
      VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
      nullptr,
      VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
      VK_ACCESS_SHADER_READ_BIT,
      VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
      VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
      VK_QUEUE_FAMILY_IGNORED,
      VK_QUEUE_FAMILY_IGNORED,
      slot.depthImage,
      {VK_IMAGE_ASPECT_DEPTH_BIT, 0, 1, 0, kStereoLayerCount}};
  vkCmdPipelineBarrier(
      slot.commandBuffer,
      VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
      VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
      0,
      0,
      nullptr,
      0,
      nullptr,
      1,
      &depthReadyBarrier);

  const VkClearValue colorClear{{{0.0f, 0.0f, 0.0f, 1.0f}}};
  const VkRenderPassBeginInfo consumerRenderPassBegin{
      VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
      nullptr,
      consumerRenderPass_,
      slot.consumerFramebuffer,
      {{0, 0}, {outputWidth_, outputHeight_}},
      1,
      &colorClear};
  vkCmdBeginRenderPass(
      slot.commandBuffer, &consumerRenderPassBegin, VK_SUBPASS_CONTENTS_INLINE);
  vkCmdBindPipeline(
      slot.commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, consumerPipeline_);
  vkCmdBindDescriptorSets(
      slot.commandBuffer,
      VK_PIPELINE_BIND_POINT_GRAPHICS,
      consumerPipelineLayout_,
      0,
      1,
      &slot.consumerDescriptorSet,
      0,
      nullptr);
  for (uint32_t eye = 0; eye < kStereoLayerCount; ++eye) {
    const VkViewport viewport{
        static_cast<float>(eye * width_),
        0.0f,
        static_cast<float>(width_),
        static_cast<float>(height_),
        0.0f,
        1.0f};
    const VkRect2D scissor{{static_cast<int32_t>(eye * width_), 0}, {width_, height_}};
    vkCmdSetViewport(slot.commandBuffer, 0, 1, &viewport);
    vkCmdSetScissor(slot.commandBuffer, 0, 1, &scissor);
    const EyePushConstant pushConstant{eye};
    vkCmdPushConstants(
        slot.commandBuffer,
        consumerPipelineLayout_,
        VK_SHADER_STAGE_FRAGMENT_BIT,
        0,
        sizeof(pushConstant),
        &pushConstant);
    vkCmdDraw(slot.commandBuffer, 3, 1, 0, 0);
  }
  vkCmdEndRenderPass(slot.commandBuffer);
  if (timestampSupported_) {
    vkCmdWriteTimestamp(
        slot.commandBuffer,
        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
        slot.queryPool,
        2);
  }

  // Production never copies depth back to host memory. The real Morphovision
  // fragment pass is the consumer; the isolated diagnostic readback stays off.
  slot.diagnosticRequested = false;
  const VkImageLayout consumerFinalLayout = slot.diagnosticRequested
                                                 ? VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
                                                 : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
  const VkImageMemoryBarrier consumerReadyBarrier{
      VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
      nullptr,
      VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
      slot.diagnosticRequested ? VK_ACCESS_TRANSFER_READ_BIT : VK_ACCESS_SHADER_READ_BIT,
      VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
      consumerFinalLayout,
      VK_QUEUE_FAMILY_IGNORED,
      VK_QUEUE_FAMILY_IGNORED,
      slot.consumerImage,
      {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
  vkCmdPipelineBarrier(
      slot.commandBuffer,
      VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
      slot.diagnosticRequested ? VK_PIPELINE_STAGE_TRANSFER_BIT
                               : VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
      0,
      0,
      nullptr,
      0,
      nullptr,
      1,
      &consumerReadyBarrier);
  if (slot.diagnosticRequested) {
    const int32_t sampleY = static_cast<int32_t>(height_ / 2U) - 1;
    const int32_t leftX = static_cast<int32_t>(width_ / 2U) - 1;
    const int32_t rightX = static_cast<int32_t>(width_ + width_ / 2U) - 1;
    const VkBufferImageCopy regions[] = {
        {0,
         0,
         0,
         {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
         {std::max(0, leftX), std::max(0, sampleY), 0},
         {2, 2, 1}},
        {16,
         0,
         0,
         {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
         {std::max(0, rightX), std::max(0, sampleY), 0},
         {2, 2, 1}}};
    vkCmdCopyImageToBuffer(
        slot.commandBuffer,
        slot.consumerImage,
        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        slot.diagnosticBuffer,
        2,
        regions);
    const VkBufferMemoryBarrier hostReadBarrier{
        VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
        nullptr,
        VK_ACCESS_TRANSFER_WRITE_BIT,
        VK_ACCESS_HOST_READ_BIT,
        VK_QUEUE_FAMILY_IGNORED,
        VK_QUEUE_FAMILY_IGNORED,
        slot.diagnosticBuffer,
        0,
        kDiagnosticByteSize};
    const VkImageMemoryBarrier sampleableOutputBarrier{
        VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        nullptr,
        VK_ACCESS_TRANSFER_READ_BIT,
        VK_ACCESS_SHADER_READ_BIT,
        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
        VK_QUEUE_FAMILY_IGNORED,
        VK_QUEUE_FAMILY_IGNORED,
        slot.consumerImage,
        {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
    vkCmdPipelineBarrier(
        slot.commandBuffer,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_HOST_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
        0,
        0,
        nullptr,
        1,
        &hostReadBarrier,
        1,
        &sampleableOutputBarrier);
  }
  if (timestampSupported_) {
    vkCmdWriteTimestamp(
        slot.commandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, slot.queryPool, 3);
  }

  result = vkEndCommandBuffer(slot.commandBuffer);
  if (result != VK_SUCCESS) {
    outcome.submit.result = result;
    outcome.submit.stage = "end-command-buffer";
    return outcome;
  }
  const VkSubmitInfo submitInfo{
      VK_STRUCTURE_TYPE_SUBMIT_INFO,
      nullptr,
      0,
      nullptr,
      nullptr,
      1,
      &slot.commandBuffer,
      0,
      nullptr};
  const uint64_t submitStart = monotonicNanoseconds();
  result = vkQueueSubmit(queue_, 1, &submitInfo, slot.fence);
  outcome.submit.queueSubmitCpuNanoseconds = monotonicNanoseconds() - submitStart;
  if (result != VK_SUCCESS) {
    outcome.submit.result = result;
    outcome.submit.stage = "queue-submit";
    return outcome;
  }

  slot.inFlight = true;
  slot.generation = ++submittedGeneration_;
  slot.metadata = metadata;
  outcome.submit.submitted = true;
  outcome.submit.result = VK_SUCCESS;
  outcome.submit.stage = "queued-before-end-no-host-wait";
  outcome.submit.generation = slot.generation;
  nextRingIndex_ = (selectedSlot + 1U) % kRingSize;
  outcome.submit.enqueueCpuNanoseconds = monotonicNanoseconds() - enqueueStart;
  return outcome;
}

void DepthGpuHandoffVulkan::destroyCopyResources() {
  if (binding_.device == VK_NULL_HANDLE) {
    sourceViews_.clear();
    slots_ = {};
    return;
  }
  bool anyInFlight = false;
  for (const Slot& slot : slots_) {
    anyInFlight = anyInFlight || slot.inFlight;
  }
  if (anyInFlight && queue_ != VK_NULL_HANDLE) {
    vkQueueWaitIdle(queue_);
  }
  for (Slot& slot : slots_) {
    if (slot.queryPool != VK_NULL_HANDLE) {
      vkDestroyQueryPool(binding_.device, slot.queryPool, nullptr);
    }
    if (slot.fence != VK_NULL_HANDLE) {
      vkDestroyFence(binding_.device, slot.fence, nullptr);
    }
    if (slot.diagnosticMapped != nullptr && slot.diagnosticMemory != VK_NULL_HANDLE) {
      vkUnmapMemory(binding_.device, slot.diagnosticMemory);
    }
    if (slot.diagnosticBuffer != VK_NULL_HANDLE) {
      vkDestroyBuffer(binding_.device, slot.diagnosticBuffer, nullptr);
    }
    if (slot.diagnosticMemory != VK_NULL_HANDLE) {
      vkFreeMemory(binding_.device, slot.diagnosticMemory, nullptr);
    }
    if (slot.consumerFramebuffer != VK_NULL_HANDLE) {
      vkDestroyFramebuffer(binding_.device, slot.consumerFramebuffer, nullptr);
    }
    for (VkFramebuffer framebuffer : slot.copyFramebuffers) {
      if (framebuffer != VK_NULL_HANDLE) {
        vkDestroyFramebuffer(binding_.device, framebuffer, nullptr);
      }
    }
    if (slot.consumerView != VK_NULL_HANDLE) {
      vkDestroyImageView(binding_.device, slot.consumerView, nullptr);
    }
    if (slot.consumerImage != VK_NULL_HANDLE) {
      vkDestroyImage(binding_.device, slot.consumerImage, nullptr);
    }
    if (slot.consumerMemory != VK_NULL_HANDLE) {
      vkFreeMemory(binding_.device, slot.consumerMemory, nullptr);
    }
    for (VkImageView view : slot.depthLayerViews) {
      if (view != VK_NULL_HANDLE) {
        vkDestroyImageView(binding_.device, view, nullptr);
      }
    }
    if (slot.depthArrayView != VK_NULL_HANDLE) {
      vkDestroyImageView(binding_.device, slot.depthArrayView, nullptr);
    }
    if (slot.depthImage != VK_NULL_HANDLE) {
      vkDestroyImage(binding_.device, slot.depthImage, nullptr);
    }
    if (slot.depthMemory != VK_NULL_HANDLE) {
      vkFreeMemory(binding_.device, slot.depthMemory, nullptr);
    }
    slot = {};
  }
  if (commandPool_ != VK_NULL_HANDLE) {
    vkDestroyCommandPool(binding_.device, commandPool_, nullptr);
  }
  commandPool_ = VK_NULL_HANDLE;
  if (consumerPipeline_ != VK_NULL_HANDLE) {
    vkDestroyPipeline(binding_.device, consumerPipeline_, nullptr);
  }
  consumerPipeline_ = VK_NULL_HANDLE;
  if (copyPipeline_ != VK_NULL_HANDLE) {
    vkDestroyPipeline(binding_.device, copyPipeline_, nullptr);
  }
  copyPipeline_ = VK_NULL_HANDLE;
  if (consumerFragmentShaderModule_ != VK_NULL_HANDLE) {
    vkDestroyShaderModule(binding_.device, consumerFragmentShaderModule_, nullptr);
  }
  consumerFragmentShaderModule_ = VK_NULL_HANDLE;
  if (copyFragmentShaderModule_ != VK_NULL_HANDLE) {
    vkDestroyShaderModule(binding_.device, copyFragmentShaderModule_, nullptr);
  }
  copyFragmentShaderModule_ = VK_NULL_HANDLE;
  if (vertexShaderModule_ != VK_NULL_HANDLE) {
    vkDestroyShaderModule(binding_.device, vertexShaderModule_, nullptr);
  }
  vertexShaderModule_ = VK_NULL_HANDLE;
  if (consumerRenderPass_ != VK_NULL_HANDLE) {
    vkDestroyRenderPass(binding_.device, consumerRenderPass_, nullptr);
  }
  consumerRenderPass_ = VK_NULL_HANDLE;
  if (copyRenderPass_ != VK_NULL_HANDLE) {
    vkDestroyRenderPass(binding_.device, copyRenderPass_, nullptr);
  }
  copyRenderPass_ = VK_NULL_HANDLE;
  if (consumerPipelineLayout_ != VK_NULL_HANDLE) {
    vkDestroyPipelineLayout(binding_.device, consumerPipelineLayout_, nullptr);
  }
  consumerPipelineLayout_ = VK_NULL_HANDLE;
  if (copyPipelineLayout_ != VK_NULL_HANDLE) {
    vkDestroyPipelineLayout(binding_.device, copyPipelineLayout_, nullptr);
  }
  copyPipelineLayout_ = VK_NULL_HANDLE;
  if (descriptorPool_ != VK_NULL_HANDLE) {
    vkDestroyDescriptorPool(binding_.device, descriptorPool_, nullptr);
  }
  descriptorPool_ = VK_NULL_HANDLE;
  if (consumerDescriptorSetLayout_ != VK_NULL_HANDLE) {
    vkDestroyDescriptorSetLayout(binding_.device, consumerDescriptorSetLayout_, nullptr);
  }
  consumerDescriptorSetLayout_ = VK_NULL_HANDLE;
  if (copyDescriptorSetLayout_ != VK_NULL_HANDLE) {
    vkDestroyDescriptorSetLayout(binding_.device, copyDescriptorSetLayout_, nullptr);
  }
  copyDescriptorSetLayout_ = VK_NULL_HANDLE;
  if (sampler_ != VK_NULL_HANDLE) {
    vkDestroySampler(binding_.device, sampler_, nullptr);
  }
  sampler_ = VK_NULL_HANDLE;
  for (VkImageView view : sourceViews_) {
    if (view != VK_NULL_HANDLE) {
      vkDestroyImageView(binding_.device, view, nullptr);
    }
  }
  sourceViews_.clear();
  width_ = 0;
  height_ = 0;
  outputWidth_ = 0;
  outputHeight_ = 0;
  submittedGeneration_ = 0;
  readyGeneration_ = 0;
  nextRingIndex_ = 0;
  readyRingIndex_ = 0;
  timestampValidBits_ = 0;
  timestampPeriodNanoseconds_ = 0.0f;
  timestampSupported_ = false;
  fragmentSampleEvidence_ = false;
  diagnosticMinimum_ = 0.0f;
  diagnosticMaximum_ = 0.0f;
}

void DepthGpuHandoffVulkan::clearCopyResources() {
  destroyCopyResources();
}

bool DepthGpuHandoffVulkan::hasPinnedLease() const {
  for (const Slot& slot : slots_) {
    if (slot.leaseId != 0) {
      return true;
    }
  }
  return false;
}

void DepthGpuHandoffVulkan::invalidateSourceSwapchain() {
  if (binding_.device == VK_NULL_HANDLE) {
    sourceViews_.clear();
    return;
  }
  bool queueDrainRequired = false;
  for (const Slot& slot : slots_) {
    // Producer work may still reference a runtime-owned source image. A lease in
    // release-pending state means the independent consumer submit has also been
    // accepted on the same queue. This is a lifecycle-only drain, never a
    // per-frame host wait.
    queueDrainRequired = queueDrainRequired || slot.inFlight || slot.leaseState == 2;
  }
  if (queueDrainRequired && queue_ != VK_NULL_HANDLE) {
    vkQueueWaitIdle(queue_);
  }
  for (VkImageView view : sourceViews_) {
    if (view != VK_NULL_HANDLE) {
      vkDestroyImageView(binding_.device, view, nullptr);
    }
  }
  sourceViews_.clear();
  if (!hasPinnedLease()) {
    destroyCopyResources();
  }
}

void DepthGpuHandoffVulkan::resetSession() {
  destroyCopyResources();
  binding_ = {};
  queue_ = VK_NULL_HANDLE;
  deviceToken_ = 0;
  sessionGeneration_ = 0;
  nextLeaseId_ = 1;
}

int32_t DepthGpuHandoffVulkan::acquireLatest(
    uint64_t expectedDeviceToken,
    uint64_t expectedSessionGeneration,
    DepthGpuLeaseSnapshot* outLease) {
  if (outLease == nullptr) {
    return -1;
  }
  if (expectedDeviceToken != deviceToken_) {
    return -3;
  }
  if (expectedSessionGeneration != sessionGeneration_) {
    return -4;
  }
  if (!isConfigured() || readyGeneration_ == 0) {
    return 1;
  }
  Slot& slot = slots_[readyRingIndex_];
  if (slot.inFlight || slot.generation != readyGeneration_ || slot.leaseId != 0) {
    return 2;
  }
  slot.leaseId = nextLeaseId_++;
  slot.leaseState = 1;
  *outLease = {
      deviceToken_,
      sessionGeneration_,
      slot.generation,
      slot.leaseId,
      readyRingIndex_,
      width_,
      height_,
      slot.depthImage,
      slot.depthArrayView,
      slot.metadata};
  return 0;
}

int32_t DepthGpuHandoffVulkan::releaseLease(uint64_t leaseId) {
  if (leaseId == 0) {
    return -1;
  }
  for (Slot& slot : slots_) {
    if (slot.leaseId == leaseId) {
      slot.leaseState = 3;
      slot.leaseId = 0;
      return 0;
    }
  }
  return -5;
}

int32_t DepthGpuHandoffVulkan::markLeaseReleasePending(uint64_t leaseId) {
  if (leaseId == 0) {
    return -1;
  }
  for (Slot& slot : slots_) {
    if (slot.leaseId == leaseId) {
      slot.leaseState = 2;
      return 0;
    }
  }
  return -5;
}

int32_t DepthGpuHandoffVulkan::leaseState(uint64_t leaseId, uint32_t* outState) const {
  if (leaseId == 0 || outState == nullptr) {
    return -1;
  }
  for (const Slot& slot : slots_) {
    if (slot.leaseId == leaseId) {
      *outState = slot.leaseState;
      return 0;
    }
  }
  *outState = 3;
  return 0;
}

uint64_t DepthGpuHandoffVulkan::deviceToken() const {
  return deviceToken_;
}

uint64_t DepthGpuHandoffVulkan::sessionGeneration() const {
  return sessionGeneration_;
}

uint64_t DepthGpuHandoffVulkan::readyGeneration() const {
  return readyGeneration_;
}

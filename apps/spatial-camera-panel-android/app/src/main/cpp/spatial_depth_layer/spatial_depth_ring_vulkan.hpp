#pragma once

#include <vulkan/vulkan.h>

#include <array>
#include <cstdint>
#include <vector>

struct VulkanBindingSnapshot {
  VkInstance instance = VK_NULL_HANDLE;
  VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
  VkDevice device = VK_NULL_HANDLE;
  uint32_t queueFamilyIndex = 0;
  uint32_t queueIndex = 0;
};

struct DepthGpuViewMetadata {
  float fov[4]{};
  float orientation[4]{};
  float position[3]{};
};

struct DepthGpuFrameMetadata {
  uint64_t frameOrdinal = 0;
  int64_t captureTime = 0;
  int64_t displayTime = 0;
  float nearZ = 0.0f;
  float farZ = 0.0f;
  std::array<DepthGpuViewMetadata, 2> depthViews{};
  std::array<DepthGpuViewMetadata, 2> renderViews{};
  int64_t renderViewDisplayTime = 0;
  uint64_t renderViewStateFlags = 0;
  uint64_t renderViewSpaceToken = 0;
  uint64_t renderViewSpaceGeneration = 0;
  uint64_t renderViewSessionGeneration = 0;
  uint32_t renderViewConfigurationType = 0;
  int32_t renderViewLocateResult = 0;
  uint32_t renderViewSource = 0;
  uint32_t validMask = 0;
};

struct DepthGpuLeaseSnapshot {
  uint64_t deviceToken = 0;
  uint64_t sessionGeneration = 0;
  uint64_t generation = 0;
  uint64_t leaseId = 0;
  uint32_t ringIndex = 0;
  uint32_t width = 0;
  uint32_t height = 0;
  VkImage image = VK_NULL_HANDLE;
  VkImageView imageView = VK_NULL_HANDLE;
  DepthGpuFrameMetadata metadata{};
};

struct DepthGpuConfigureOutcome {
  bool configured = false;
  VkResult result = VK_SUCCESS;
  const char* stage = "not-attempted";
  bool deviceLocalD16Supported = false;
  bool timestampSupported = false;
  uint32_t timestampValidBits = 0;
  float timestampPeriodNanoseconds = 0.0f;
  bool ahardwareBufferFunctionsCallable = false;
  bool externalSemaphoreFdFunctionsCallable = false;
};

struct DepthGpuSubmitOutcome {
  bool attempted = false;
  bool submitted = false;
  bool dropped = false;
  VkResult result = VK_SUCCESS;
  const char* stage = "not-attempted";
  uint64_t generation = 0;
  uint32_t ringIndex = 0;
  uint64_t enqueueCpuNanoseconds = 0;
  uint64_t queueSubmitCpuNanoseconds = 0;
};

struct DepthGpuCompletionOutcome {
  bool completionObserved = false;
  bool sampleable = false;
  bool fragmentSampleEvidence = false;
  VkResult result = VK_SUCCESS;
  const char* stage = "no-completion";
  uint64_t generation = 0;
  uint64_t frameOrdinal = 0;
  uint32_t ringIndex = 0;
  uint32_t completedCount = 0;
  uint32_t inFlightCount = 0;
  uint32_t staleFrameCount = 0;
  uint64_t pollCpuNanoseconds = 0;
  uint64_t gpuDepthCopyNanoseconds = 0;
  uint64_t gpuFragmentConsumerNanoseconds = 0;
  uint64_t gpuTotalNanoseconds = 0;
  float diagnosticMinimum = 0.0f;
  float diagnosticMaximum = 0.0f;
  DepthGpuFrameMetadata metadata{};
};

struct DepthGpuEnqueueOutcome {
  DepthGpuSubmitOutcome submit{};
  DepthGpuCompletionOutcome completion{};
};

class DepthGpuHandoffVulkan {
 public:
  bool bind(const VulkanBindingSnapshot& binding, uint64_t sessionGeneration);
  DepthGpuConfigureOutcome configure(
      uint32_t width, uint32_t height, const std::vector<VkImage>& sourceImages);
  DepthGpuEnqueueOutcome enqueue(
      uint32_t sourceImageIndex, const DepthGpuFrameMetadata& metadata);
  DepthGpuCompletionOutcome poll(uint64_t currentFrameOrdinal);
  int32_t acquireLatest(
      uint64_t expectedDeviceToken,
      uint64_t expectedSessionGeneration,
      DepthGpuLeaseSnapshot* outLease);
  int32_t releaseLease(uint64_t leaseId);
  int32_t markLeaseReleasePending(uint64_t leaseId);
  int32_t leaseState(uint64_t leaseId, uint32_t* outState) const;
  void invalidateSourceSwapchain();
  uint64_t deviceToken() const;
  uint64_t sessionGeneration() const;
  uint64_t readyGeneration() const;
  void clearCopyResources();
  void resetSession();
  bool isBound() const;
  bool isConfigured() const;

 private:
  static constexpr uint32_t kStereoLayerCount = 2;
  static constexpr uint32_t kRingSize = 3;
  static constexpr uint32_t kTimestampCount = 4;
  static constexpr VkDeviceSize kDiagnosticByteSize = 32;

  struct Slot {
    VkImage depthImage = VK_NULL_HANDLE;
    VkDeviceMemory depthMemory = VK_NULL_HANDLE;
    VkImageView depthArrayView = VK_NULL_HANDLE;
    std::array<VkImageView, kStereoLayerCount> depthLayerViews{};
    std::array<VkFramebuffer, kStereoLayerCount> copyFramebuffers{};
    VkImage consumerImage = VK_NULL_HANDLE;
    VkDeviceMemory consumerMemory = VK_NULL_HANDLE;
    VkImageView consumerView = VK_NULL_HANDLE;
    VkFramebuffer consumerFramebuffer = VK_NULL_HANDLE;
    VkBuffer diagnosticBuffer = VK_NULL_HANDLE;
    VkDeviceMemory diagnosticMemory = VK_NULL_HANDLE;
    void* diagnosticMapped = nullptr;
    bool diagnosticCoherent = false;
    VkDescriptorSet copyDescriptorSet = VK_NULL_HANDLE;
    VkDescriptorSet consumerDescriptorSet = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkQueryPool queryPool = VK_NULL_HANDLE;
    bool inFlight = false;
    bool diagnosticRequested = false;
    uint64_t leaseId = 0;
    uint32_t leaseState = 0;
    uint64_t generation = 0;
    DepthGpuFrameMetadata metadata{};
  };

  void destroyCopyResources();
  bool hasPinnedLease() const;
  bool findMemoryType(
      uint32_t typeBits,
      VkMemoryPropertyFlags required,
      VkMemoryPropertyFlags preferred,
      uint32_t* memoryTypeIndex,
      bool* coherent = nullptr) const;
  VkResult createImage(
      VkFormat format,
      uint32_t width,
      uint32_t height,
      uint32_t arrayLayers,
      VkImageUsageFlags usage,
      VkImage* image,
      VkDeviceMemory* memory);
  VkResult createShaderModule(
      const uint32_t* words, size_t byteSize, VkShaderModule* shaderModule) const;
  DepthGpuCompletionOutcome pollCompletions(uint64_t currentFrameOrdinal);
  uint64_t timestampDeltaNanoseconds(uint64_t begin, uint64_t end) const;

  VulkanBindingSnapshot binding_{};
  VkQueue queue_ = VK_NULL_HANDLE;
  uint32_t width_ = 0;
  uint32_t height_ = 0;
  uint32_t outputWidth_ = 0;
  uint32_t outputHeight_ = 0;
  std::vector<VkImageView> sourceViews_;
  VkSampler sampler_ = VK_NULL_HANDLE;
  VkDescriptorSetLayout copyDescriptorSetLayout_ = VK_NULL_HANDLE;
  VkDescriptorSetLayout consumerDescriptorSetLayout_ = VK_NULL_HANDLE;
  VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
  VkPipelineLayout copyPipelineLayout_ = VK_NULL_HANDLE;
  VkPipelineLayout consumerPipelineLayout_ = VK_NULL_HANDLE;
  VkRenderPass copyRenderPass_ = VK_NULL_HANDLE;
  VkRenderPass consumerRenderPass_ = VK_NULL_HANDLE;
  VkPipeline copyPipeline_ = VK_NULL_HANDLE;
  VkPipeline consumerPipeline_ = VK_NULL_HANDLE;
  VkShaderModule vertexShaderModule_ = VK_NULL_HANDLE;
  VkShaderModule copyFragmentShaderModule_ = VK_NULL_HANDLE;
  VkShaderModule consumerFragmentShaderModule_ = VK_NULL_HANDLE;
  VkCommandPool commandPool_ = VK_NULL_HANDLE;
  std::array<Slot, kRingSize> slots_{};
  uint64_t submittedGeneration_ = 0;
  uint64_t readyGeneration_ = 0;
  uint32_t nextRingIndex_ = 0;
  uint32_t readyRingIndex_ = 0;
  uint32_t timestampValidBits_ = 0;
  float timestampPeriodNanoseconds_ = 0.0f;
  bool timestampSupported_ = false;
  bool fragmentSampleEvidence_ = false;
  float diagnosticMinimum_ = 0.0f;
  float diagnosticMaximum_ = 0.0f;
  uint64_t deviceToken_ = 0;
  uint64_t sessionGeneration_ = 0;
  uint64_t nextLeaseId_ = 1;
};

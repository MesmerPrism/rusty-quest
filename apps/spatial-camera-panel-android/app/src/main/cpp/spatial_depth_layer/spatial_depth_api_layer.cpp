#include "openxr_layer_abi.hpp"
#include "spatial_depth_queue_broker.hpp"
#include "spatial_depth_handoff_abi.h"
#include "spatial_depth_ring_vulkan.hpp"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdarg>
#include <cstring>
#include <dlfcn.h>
#include <deque>
#include <mutex>
#include <unordered_map>
#include <unordered_set>
#include <vector>
#include <unistd.h>

namespace {

constexpr char kLayerName[] = "XR_APILAYER_MESMERPRISM_spatial_sdk_depth_handoff";
constexpr char kDepthExtension[] = "XR_META_environment_depth";
constexpr char kTag[] = "RqSpatialDepthHandoff";
constexpr char kSchema[] = "schema=rusty.quest.spatial_sdk_depth_handoff.v2";
constexpr bool kLayerOwnedDepthAcquisitionEnabled = false;
constexpr XrViewConfigurationType kPrimaryStereoViewConfiguration = 2;
constexpr XrViewStateFlags kOrientationValidBit = 0x1ULL;
constexpr XrViewStateFlags kPositionValidBit = 0x2ULL;
constexpr XrViewStateFlags kRequiredViewValidityBits =
    kOrientationValidBit | kPositionValidBit;

enum class YcbcrFeatureStructSource : uint32_t {
  kNone = 0,
  kDedicated = 1,
  kVulkan11 = 2,
  kIllegalDuplicate = 3,
};

const char* ycbcrFeatureStructSourceName(YcbcrFeatureStructSource source) {
  switch (source) {
    case YcbcrFeatureStructSource::kDedicated:
      return "dedicated";
    case YcbcrFeatureStructSource::kVulkan11:
      return "vulkan11";
    case YcbcrFeatureStructSource::kIllegalDuplicate:
      return "illegal-duplicate";
    case YcbcrFeatureStructSource::kNone:
    default:
      return "absent";
  }
}

struct YcbcrFeatureRequestInspection {
  YcbcrFeatureStructSource source = YcbcrFeatureStructSource::kNone;
  bool requested = false;
  bool dedicatedPresent = false;
  bool vulkan11Present = false;
  bool targetAtChainHead = false;
};

struct DirectRenderViewOutcome {
  bool attempted = false;
  bool spaceAccepted = false;
  bool valid = false;
  bool interceptedExactMatch = false;
  bool movingHeadObserved = false;
  XrResult result = XR_ERROR_VALIDATION_FAILURE;
  XrViewConfigurationType viewConfigurationType = 0;
  XrTime displayTime = 0;
  XrSpace space = nullptr;
  uint64_t spaceOrdinal = 0;
  uint64_t sessionOrdinal = 0;
  XrViewStateFlags stateFlags = 0;
  uint32_t viewCount = 0;
  std::array<XrView, 2> views{};
};

enum ApplicationDepthFunctionBit : uint32_t {
  kCreateProviderBit = 1U << 0U,
  kDestroyProviderBit = 1U << 1U,
  kStartProviderBit = 1U << 2U,
  kStopProviderBit = 1U << 3U,
  kCreateSwapchainBit = 1U << 4U,
  kDestroySwapchainBit = 1U << 5U,
  kEnumerateSwapchainImagesBit = 1U << 6U,
  kGetSwapchainStateBit = 1U << 7U,
  kAcquireImageBit = 1U << 8U,
};

void marker(const char* format, ...) {
  char payload[1400]{};
  va_list arguments;
  va_start(arguments, format);
  vsnprintf(payload, sizeof(payload), format, arguments);
  va_end(arguments);
  __android_log_print(ANDROID_LOG_INFO, kTag, "%s %s", kSchema, payload);
}

struct Dispatch {
  PFN_xrGetInstanceProcAddr getInstanceProcAddr = nullptr;
  PFN_xrDestroyInstance destroyInstance = nullptr;
  PFN_xrCreateVulkanInstanceKHR createVulkanInstance = nullptr;
  PFN_xrCreateVulkanDeviceKHR createVulkanDevice = nullptr;
  PFN_xrCreateSession createSession = nullptr;
  PFN_xrDestroySession destroySession = nullptr;
  PFN_xrCreateReferenceSpace createReferenceSpace = nullptr;
  PFN_xrDestroySpace destroySpace = nullptr;
  PFN_xrLocateViews locateViews = nullptr;
  PFN_xrWaitFrame waitFrame = nullptr;
  PFN_xrBeginFrame beginFrame = nullptr;
  PFN_xrEndFrame endFrame = nullptr;
  PFN_xrCreateEnvironmentDepthProviderMETA createEnvironmentDepthProvider = nullptr;
  PFN_xrDestroyEnvironmentDepthProviderMETA destroyEnvironmentDepthProvider = nullptr;
  PFN_xrStartEnvironmentDepthProviderMETA startEnvironmentDepthProvider = nullptr;
  PFN_xrStopEnvironmentDepthProviderMETA stopEnvironmentDepthProvider = nullptr;
  PFN_xrCreateEnvironmentDepthSwapchainMETA createEnvironmentDepthSwapchain = nullptr;
  PFN_xrDestroyEnvironmentDepthSwapchainMETA destroyEnvironmentDepthSwapchain = nullptr;
  PFN_xrEnumerateEnvironmentDepthSwapchainImagesMETA enumerateEnvironmentDepthSwapchainImages = nullptr;
  PFN_xrGetEnvironmentDepthSwapchainStateMETA getEnvironmentDepthSwapchainState = nullptr;
  PFN_xrAcquireEnvironmentDepthImageMETA acquireEnvironmentDepthImage = nullptr;
};

struct ProbeState {
  Dispatch downstream{};
  XrInstance instance = nullptr;
  XrSession session = nullptr;
  VkInstance vulkanInstance = VK_NULL_HANDLE;
  VulkanBindingSnapshot sdkVulkanBinding{};
  VkQueue sdkQueue = VK_NULL_HANDLE;
  uint64_t consumerContextToken = 0;
  XrSpace localSpace = nullptr;
  XrEnvironmentDepthProviderMETA depthProvider = nullptr;
  XrEnvironmentDepthSwapchainMETA depthSwapchain = nullptr;
  XrEnvironmentDepthProviderMETA applicationDepthProvider = nullptr;
  XrEnvironmentDepthSwapchainMETA applicationDepthSwapchain = nullptr;
  uint64_t instanceOrdinal = 0;
  uint64_t sessionOrdinal = 0;
  uint64_t frameOrdinal = 0;
  uint64_t applicationAcquireOrdinal = 0;
  XrTime predictedDisplayTime = 0;
  XrTime renderViewDisplayTime = 0;
  XrSpace renderViewSpace = nullptr;
  XrViewConfigurationType renderViewConfigurationType = 0;
  uint64_t renderViewSessionOrdinal = 0;
  uint64_t renderViewInterceptOrdinal = 0;
  XrViewStateFlags renderViewStateFlags = 0;
  std::array<XrView, 2> renderViews{};
  uint32_t renderViewCount = 0;
  std::array<XrView, 2> previousDirectRenderViews{};
  uint64_t directRenderViewLocateOrdinal = 0;
  uint64_t directRenderViewSuccessCount = 0;
  uint64_t directRenderViewFailureCount = 0;
  uint64_t lastDirectRenderViewMarkerSessionOrdinal = 0;
  uint64_t lastDirectRenderViewMarkerSpaceOrdinal = 0;
  bool directRenderViewPreviousValid = false;
  bool movingHeadObserved = false;
  bool directRenderViewMarkerStateObserved = false;
  bool lastDirectRenderViewMarkerValid = false;
  uint64_t nextSpaceOrdinal = 1;
  std::unordered_map<XrSpace, uint64_t> liveSpaceOrdinals;
  std::unordered_set<XrSpace> destroyedSpaces;
  XrResult acquireResult = XR_ENVIRONMENT_DEPTH_NOT_AVAILABLE_META;
  uint32_t acquiredSwapchainIndex = 0;
  uint32_t applicationDepthFunctionMask = 0;
  uint32_t applicationDepthWidth = 0;
  uint32_t applicationDepthHeight = 0;
  std::vector<VkImage> applicationDepthImages;
  XrEnvironmentDepthImageMETA retainedDepthMetadata{};
  VkResult applicationGpuResult = VK_SUCCESS;
  uint64_t applicationGpuSubmittedGeneration = 0;
  uint64_t applicationGpuReadyGeneration = 0;
  uint64_t applicationGpuReadyFrameOrdinal = 0;
  uint64_t applicationGpuCopyNanoseconds = 0;
  uint64_t applicationGpuConsumerNanoseconds = 0;
  uint64_t applicationGpuTotalNanoseconds = 0;
  uint64_t applicationGpuEnqueueCpuNanoseconds = 0;
  uint64_t applicationGpuSubmitCpuNanoseconds = 0;
  uint64_t applicationGpuPollCpuNanoseconds = 0;
  uint32_t applicationGpuReadyRingIndex = 0;
  uint32_t applicationGpuStaleFrameCount = 0;
  float applicationGpuDiagnosticMinimum = 0.0f;
  float applicationGpuDiagnosticMaximum = 0.0f;
  bool depthExtensionEnabled = false;
  bool callableDepthFunctions = false;
  bool depthSetupAttempted = false;
  bool depthStarted = false;
  bool applicationDepthCreateAttempted = false;
  bool applicationDepthStarted = false;
  bool frameOpen = false;
  bool acquiredThisFrame = false;
  bool applicationGpuAttemptedThisFrame = false;
  bool applicationGpuSubmittedThisFrame = false;
  bool applicationGpuDroppedThisFrame = false;
  bool applicationGpuSampleable = false;
  bool applicationGpuFragmentSampleEvidence = false;
  bool vulkanInstanceCreateObserved = false;
  bool vulkanDeviceCreateObserved = false;
  bool vulkanInstanceSurfaceRequested = false;
  bool vulkanInstanceAndroidSurfaceRequested = false;
  bool vulkanDeviceSwapchainRequested = false;
  bool vulkanDeviceAhbRequested = false;
  bool vulkanDeviceYcbcrExtensionRequested = false;
  bool vulkanDeviceYcbcrFeatureRequested = false;
  bool vulkanDeviceYcbcrFeatureRequestedBefore = false;
  bool vulkanDeviceYcbcrFeaturePhysicallySupported = false;
  bool vulkanDeviceYcbcrFeatures2Callable = false;
  bool vulkanDeviceYcbcrFeatureAugmentationAttempted = false;
  bool vulkanDeviceYcbcrFeatureAugmentationLegal = false;
  bool vulkanDeviceYcbcrFeatureAugmented = false;
  bool vulkanDeviceExternalSemaphoreFdRequestedBefore = false;
  bool vulkanDeviceExternalSemaphoreFdPhysicallySupported = false;
  bool vulkanDeviceExternalSemaphoreFdEnumerationCallable = false;
  bool vulkanDeviceExternalSemaphoreFdAugmented = false;
  bool vulkanDeviceExternalSemaphoreFdRequested = false;
  YcbcrFeatureStructSource vulkanDeviceYcbcrFeatureStructSource =
      YcbcrFeatureStructSource::kNone;
};

std::mutex gMutex;
ProbeState gState;
DepthGpuHandoffVulkan gDepthGpuHandoff;
DepthConsumerBridge gDepthConsumerBridge;
uint64_t gNextInstanceOrdinal = 1;
uint64_t gNextSessionOrdinal = 1;

struct SpatialSubmitRequestV2 {
  rq_spatial_depth_submit_present_v2 request{};
  rq_depth_gpu_request_result_v1 result{};
};

constexpr size_t kMaximumSpatialSubmitRequests = 32;
std::deque<uint64_t> gSpatialSubmitQueue;
std::unordered_map<uint64_t, SpatialSubmitRequestV2> gSpatialSubmitRequests;
bool gSpatialShutdownRequested = false;

uint64_t nextProcessStableSessionGeneration() {
  return gNextSessionOrdinal++;
}

uint64_t monotonicNanosecondsV2() {
  return static_cast<uint64_t>(
      std::chrono::duration_cast<std::chrono::nanoseconds>(
          std::chrono::steady_clock::now().time_since_epoch())
          .count());
}

void resetSpatialSubmitBrokerLocked() {
  gSpatialSubmitQueue.clear();
  gSpatialSubmitRequests.clear();
  gSpatialShutdownRequested = false;
}

int32_t validateSpatialRequestLocked(
    uint64_t expectedContextToken,
    uint64_t expectedDeviceToken,
    uint64_t expectedSessionGeneration) {
  if (gSpatialShutdownRequested) {
    return RQ_DEPTH_GPU_STATUS_SHUTDOWN;
  }
  if (gState.session == nullptr || gState.sdkVulkanBinding.device == VK_NULL_HANDLE ||
      gState.sdkQueue == VK_NULL_HANDLE) {
    return RQ_DEPTH_GPU_STATUS_NOT_READY;
  }
  if (expectedContextToken != gState.consumerContextToken || expectedDeviceToken == 0 ||
      expectedDeviceToken != gDepthConsumerBridge.deviceToken()) {
    return RQ_DEPTH_GPU_STATUS_DEVICE_MISMATCH;
  }
  if (expectedSessionGeneration == 0 ||
      expectedSessionGeneration != gState.sessionOrdinal) {
    return RQ_DEPTH_GPU_STATUS_SESSION_MISMATCH;
  }
  return RQ_DEPTH_GPU_STATUS_OK;
}

void failSpatialSubmitRequestLocked(SpatialSubmitRequestV2& request, int32_t status) {
  request.result.state = RQ_DEPTH_GPU_REQUEST_STATE_FAILED;
  request.result.status = status;
  request.result.completed_monotonic_ns = monotonicNanosecondsV2();
}

void processSpatialSubmitRequestsLocked(uint64_t frameOrdinal, bool matchingBegin) {
  while (!gSpatialSubmitQueue.empty()) {
    const uint64_t requestId = gSpatialSubmitQueue.front();
    gSpatialSubmitQueue.pop_front();
    auto iterator = gSpatialSubmitRequests.find(requestId);
    if (iterator == gSpatialSubmitRequests.end()) {
      continue;
    }
    SpatialSubmitRequestV2& queued = iterator->second;
    const int32_t validation = validateSpatialRequestLocked(
        queued.request.expected_context_token,
        queued.request.expected_device_token,
        queued.request.expected_session_generation);
    if (validation != RQ_DEPTH_GPU_STATUS_OK) {
      failSpatialSubmitRequestLocked(queued, validation);
      continue;
    }
    if (!matchingBegin || !gState.frameOpen) {
      failSpatialSubmitRequestLocked(queued, RQ_DEPTH_GPU_STATUS_OUT_OF_WINDOW);
      marker("channel=queue-broker status=request-rejected reason=outside-matching-begin-end requestId=%llu sessionOrdinal=%llu frameOrdinal=%llu",
             static_cast<unsigned long long>(requestId),
             static_cast<unsigned long long>(gState.sessionOrdinal),
             static_cast<unsigned long long>(frameOrdinal));
      continue;
    }

    const VkCommandBuffer commandBuffer = reinterpret_cast<VkCommandBuffer>(
        static_cast<uintptr_t>(queued.request.command_buffer_handle));
    const VkSemaphore waitSemaphore = reinterpret_cast<VkSemaphore>(
        static_cast<uintptr_t>(queued.request.wait_semaphore_handle));
    const VkSemaphore signalSemaphore = reinterpret_cast<VkSemaphore>(
        static_cast<uintptr_t>(queued.request.signal_semaphore_handle));
    const VkFence fence = reinterpret_cast<VkFence>(
        static_cast<uintptr_t>(queued.request.fence_handle));
    const VkSwapchainKHR swapchain = reinterpret_cast<VkSwapchainKHR>(
        static_cast<uintptr_t>(queued.request.swapchain_handle));
    if (commandBuffer == VK_NULL_HANDLE || fence == VK_NULL_HANDLE) {
      failSpatialSubmitRequestLocked(queued, RQ_DEPTH_GPU_STATUS_INVALID_ARGUMENT);
      continue;
    }
    if (queued.request.lease_id != 0) {
      uint32_t leaseState = 0;
      const int32_t leaseStatus = gDepthGpuHandoff.leaseState(
          queued.request.lease_id, &leaseState);
      if (leaseStatus != RQ_DEPTH_GPU_STATUS_OK || leaseState != 1) {
        failSpatialSubmitRequestLocked(queued, RQ_DEPTH_GPU_STATUS_STALE_LEASE);
        marker("channel=queue-broker status=request-rejected reason=lease-not-acquired requestId=%llu leaseId=%llu leaseStatus=%d leaseState=%u",
               static_cast<unsigned long long>(requestId),
               static_cast<unsigned long long>(queued.request.lease_id),
               leaseStatus,
               leaseState);
        continue;
      }
    }

    const VkPipelineStageFlags waitStage = queued.request.wait_stage_mask == 0
                                               ? VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
                                               : queued.request.wait_stage_mask;
    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    if (waitSemaphore != VK_NULL_HANDLE) {
      submitInfo.waitSemaphoreCount = 1;
      submitInfo.pWaitSemaphores = &waitSemaphore;
      submitInfo.pWaitDstStageMask = &waitStage;
    }
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &commandBuffer;
    if (signalSemaphore != VK_NULL_HANDLE) {
      submitInfo.signalSemaphoreCount = 1;
      submitInfo.pSignalSemaphores = &signalSemaphore;
    }

    queued.result.submitted_monotonic_ns = monotonicNanosecondsV2();
    const uint64_t submitStart = queued.result.submitted_monotonic_ns;
    VkResult result = vkQueueSubmit(gState.sdkQueue, 1, &submitInfo, fence);
    queued.result.queue_submit_cpu_ns = monotonicNanosecondsV2() - submitStart;
    queued.result.vk_result = result;
    if (result == VK_SUCCESS) {
      queued.result.qualification_flags |=
          RQ_SPATIAL_DEPTH_RESULT_QUEUE_SUBMIT_ACCEPTED;
      if (queued.request.lease_id != 0) {
        const int32_t pendingStatus =
            gDepthGpuHandoff.markLeaseReleasePending(queued.request.lease_id);
        if (pendingStatus != RQ_DEPTH_GPU_STATUS_OK) {
          // Submission has already entered the SDK queue. Drain it before
          // reporting the internal lease invariant failure so no copied image
          // can be destroyed while referenced by the accepted command buffer.
          vkQueueWaitIdle(gState.sdkQueue);
          result = VK_ERROR_INITIALIZATION_FAILED;
          marker("channel=queue-broker status=internal-invariant-failed reason=accepted-submit-lease-missing requestId=%llu leaseId=%llu pendingStatus=%d queueDrained=true",
                 static_cast<unsigned long long>(requestId),
                 static_cast<unsigned long long>(queued.request.lease_id),
                 pendingStatus);
        }
      }
    }
    if (result == VK_SUCCESS &&
        (queued.request.flags & RQ_SPATIAL_DEPTH_SUBMIT_PRESENT) != 0U) {
      if (swapchain == VK_NULL_HANDLE || signalSemaphore == VK_NULL_HANDLE) {
        result = VK_ERROR_INITIALIZATION_FAILED;
      } else {
        VkPresentInfoKHR presentInfo{};
        presentInfo.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
        presentInfo.waitSemaphoreCount = 1;
        presentInfo.pWaitSemaphores = &signalSemaphore;
        presentInfo.swapchainCount = 1;
        presentInfo.pSwapchains = &swapchain;
        presentInfo.pImageIndices = &queued.request.image_index;
        result = vkQueuePresentKHR(gState.sdkQueue, &presentInfo);
        if (result == VK_SUCCESS || result == VK_SUBOPTIMAL_KHR) {
          queued.result.qualification_flags |=
              RQ_SPATIAL_DEPTH_RESULT_PRESENT_ACCEPTED;
        }
      }
    }
    queued.result.vk_result = result;
    queued.result.completed_monotonic_ns = monotonicNanosecondsV2();
    queued.result.state = result == VK_SUCCESS || result == VK_SUBOPTIMAL_KHR
                              ? RQ_DEPTH_GPU_REQUEST_STATE_COMPLETE
                              : RQ_DEPTH_GPU_REQUEST_STATE_FAILED;
    queued.result.status = queued.result.state == RQ_DEPTH_GPU_REQUEST_STATE_COMPLETE
                               ? RQ_DEPTH_GPU_STATUS_OK
                               : RQ_DEPTH_GPU_STATUS_VULKAN_FAILURE;
    if (requestId <= 3 || requestId % 300 == 0 || queued.result.status != RQ_DEPTH_GPU_STATUS_OK) {
      marker("channel=queue-broker status=request-processed requestId=%llu sessionOrdinal=%llu frameOrdinal=%llu inMatchingBeginEnd=true submitResult=%d presentRequested=%s queueSubmitAccepted=%s presentAccepted=%s qualificationFlags=0x%x appSubmissionAuthority=layer-broker sdkRuntimeSubmissionAuthority=opaque",
             static_cast<unsigned long long>(requestId),
             static_cast<unsigned long long>(gState.sessionOrdinal),
             static_cast<unsigned long long>(frameOrdinal),
             result,
             (queued.request.flags & RQ_SPATIAL_DEPTH_SUBMIT_PRESENT) != 0U ? "true" : "false",
             (queued.result.qualification_flags &
              RQ_SPATIAL_DEPTH_RESULT_QUEUE_SUBMIT_ACCEPTED) != 0U
                 ? "true"
                 : "false",
             (queued.result.qualification_flags &
              RQ_SPATIAL_DEPTH_RESULT_PRESENT_ACCEPTED) != 0U
                 ? "true"
                 : "false",
             queued.result.qualification_flags);
    }
  }
}

template <typename Function>
Function loadFunction(PFN_xrGetInstanceProcAddr getInstanceProcAddr, XrInstance instance, const char* name) {
  PFN_xrVoidFunction function = nullptr;
  if (getInstanceProcAddr == nullptr ||
      getInstanceProcAddr(instance, name, &function) != XR_SUCCESS || function == nullptr) {
    return nullptr;
  }
  return reinterpret_cast<Function>(function);
}

bool requestedExtension(const XrInstanceCreateInfo* createInfo, const char* extension) {
  if (createInfo == nullptr || extension == nullptr) {
    return false;
  }
  for (uint32_t index = 0; index < createInfo->enabledExtensionCount; ++index) {
    const char* candidate = createInfo->enabledExtensionNames[index];
    if (candidate != nullptr && std::strcmp(candidate, extension) == 0) {
      return true;
    }
  }
  return false;
}

uint32_t countRequestedVulkanExtension(
    uint32_t count, const char* const* names, const char* extension) {
  if (names == nullptr || extension == nullptr) {
    return 0;
  }
  uint32_t matches = 0;
  for (uint32_t index = 0; index < count; ++index) {
    if (names[index] != nullptr && std::strcmp(names[index], extension) == 0) {
      ++matches;
    }
  }
  return matches;
}

YcbcrFeatureRequestInspection inspectSamplerYcbcrFeatureRequest(
    const VkDeviceCreateInfo* createInfo) {
  YcbcrFeatureRequestInspection inspection{};
  if (createInfo == nullptr) {
    return inspection;
  }
  const auto* head = static_cast<const VkBaseInStructure*>(createInfo->pNext);
  const auto* chain = head;
  while (chain != nullptr) {
    if (chain->sType ==
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES) {
      inspection.dedicatedPresent = true;
      const auto* features =
          reinterpret_cast<const VkPhysicalDeviceSamplerYcbcrConversionFeatures*>(chain);
      inspection.requested =
          inspection.requested || features->samplerYcbcrConversion == VK_TRUE;
      inspection.targetAtChainHead = inspection.targetAtChainHead || chain == head;
    } else if (chain->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES) {
      inspection.vulkan11Present = true;
      const auto* features =
          reinterpret_cast<const VkPhysicalDeviceVulkan11Features*>(chain);
      inspection.requested =
          inspection.requested || features->samplerYcbcrConversion == VK_TRUE;
      inspection.targetAtChainHead = inspection.targetAtChainHead || chain == head;
    }
    chain = chain->pNext;
  }
  if (inspection.dedicatedPresent && inspection.vulkan11Present) {
    inspection.source = YcbcrFeatureStructSource::kIllegalDuplicate;
  } else if (inspection.dedicatedPresent) {
    inspection.source = YcbcrFeatureStructSource::kDedicated;
  } else if (inspection.vulkan11Present) {
    inspection.source = YcbcrFeatureStructSource::kVulkan11;
  }
  return inspection;
}

bool querySamplerYcbcrPhysicalSupport(
    VkInstance vulkanInstance,
    VkPhysicalDevice physicalDevice,
    PFN_vkGetInstanceProcAddr getInstanceProcAddr,
    bool* callable) {
  if (callable != nullptr) {
    *callable = false;
  }
  if (physicalDevice == VK_NULL_HANDLE) {
    return false;
  }
  PFN_vkGetPhysicalDeviceFeatures2 getFeatures2 = nullptr;
  if (getInstanceProcAddr != nullptr && vulkanInstance != VK_NULL_HANDLE) {
    getFeatures2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
        getInstanceProcAddr(vulkanInstance, "vkGetPhysicalDeviceFeatures2"));
  }
  if (getFeatures2 == nullptr) {
    getFeatures2 = &vkGetPhysicalDeviceFeatures2;
  }
  if (getFeatures2 == nullptr) {
    return false;
  }
  VkPhysicalDeviceVulkan11Features vulkan11Features{};
  vulkan11Features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES;
  VkPhysicalDeviceFeatures2 features2{};
  features2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
  features2.pNext = &vulkan11Features;
  getFeatures2(physicalDevice, &features2);
  if (callable != nullptr) {
    *callable = true;
  }
  return vulkan11Features.samplerYcbcrConversion == VK_TRUE;
}

bool queryDeviceExtensionSupport(
    VkInstance vulkanInstance,
    VkPhysicalDevice physicalDevice,
    PFN_vkGetInstanceProcAddr getInstanceProcAddr,
    const char* extensionName,
    bool* callable) {
  if (callable != nullptr) {
    *callable = false;
  }
  if (physicalDevice == VK_NULL_HANDLE || extensionName == nullptr) {
    return false;
  }
  PFN_vkEnumerateDeviceExtensionProperties enumerateExtensions = nullptr;
  if (getInstanceProcAddr != nullptr && vulkanInstance != VK_NULL_HANDLE) {
    enumerateExtensions = reinterpret_cast<PFN_vkEnumerateDeviceExtensionProperties>(
        getInstanceProcAddr(
            vulkanInstance, "vkEnumerateDeviceExtensionProperties"));
  }
  if (enumerateExtensions == nullptr) {
    enumerateExtensions = &vkEnumerateDeviceExtensionProperties;
  }
  if (enumerateExtensions == nullptr) {
    return false;
  }
  if (callable != nullptr) {
    *callable = true;
  }
  uint32_t extensionCount = 0;
  if (enumerateExtensions(physicalDevice, nullptr, &extensionCount, nullptr) !=
          VK_SUCCESS ||
      extensionCount == 0U) {
    return false;
  }
  std::vector<VkExtensionProperties> extensions(extensionCount);
  if (enumerateExtensions(
          physicalDevice, nullptr, &extensionCount, extensions.data()) != VK_SUCCESS) {
    return false;
  }
  return std::any_of(
      extensions.begin(), extensions.begin() + extensionCount,
      [extensionName](const VkExtensionProperties& extension) {
        return std::strcmp(extension.extensionName, extensionName) == 0;
      });
}

bool queryInstanceExtensionSupport(
    PFN_vkGetInstanceProcAddr getInstanceProcAddr,
    const char* extensionName,
    bool* callable) {
  if (callable != nullptr) {
    *callable = false;
  }
  if (extensionName == nullptr) {
    return false;
  }
  PFN_vkEnumerateInstanceExtensionProperties enumerateExtensions = nullptr;
  if (getInstanceProcAddr != nullptr) {
    enumerateExtensions = reinterpret_cast<PFN_vkEnumerateInstanceExtensionProperties>(
        getInstanceProcAddr(
            VK_NULL_HANDLE, "vkEnumerateInstanceExtensionProperties"));
  }
  if (enumerateExtensions == nullptr) {
    enumerateExtensions = &vkEnumerateInstanceExtensionProperties;
  }
  if (enumerateExtensions == nullptr) {
    return false;
  }
  if (callable != nullptr) {
    *callable = true;
  }
  uint32_t extensionCount = 0;
  if (enumerateExtensions(nullptr, &extensionCount, nullptr) != VK_SUCCESS ||
      extensionCount == 0U) {
    return false;
  }
  std::vector<VkExtensionProperties> extensions(extensionCount);
  if (enumerateExtensions(nullptr, &extensionCount, extensions.data()) != VK_SUCCESS) {
    return false;
  }
  return std::any_of(
      extensions.begin(), extensions.begin() + extensionCount,
      [extensionName](const VkExtensionProperties& extension) {
        return std::strcmp(extension.extensionName, extensionName) == 0;
      });
}

float renderViewMotionDelta(
    const std::array<XrView, 2>& before, const std::array<XrView, 2>& after) {
  float maximum = 0.0F;
  for (uint32_t eye = 0; eye < 2; ++eye) {
    const float valuesBefore[] = {
        before[eye].pose.orientation.x,
        before[eye].pose.orientation.y,
        before[eye].pose.orientation.z,
        before[eye].pose.orientation.w,
        before[eye].pose.position.x,
        before[eye].pose.position.y,
        before[eye].pose.position.z};
    const float valuesAfter[] = {
        after[eye].pose.orientation.x,
        after[eye].pose.orientation.y,
        after[eye].pose.orientation.z,
        after[eye].pose.orientation.w,
        after[eye].pose.position.x,
        after[eye].pose.position.y,
        after[eye].pose.position.z};
    for (size_t index = 0; index < std::size(valuesBefore); ++index) {
      maximum = std::max(maximum, std::abs(valuesAfter[index] - valuesBefore[index]));
    }
  }
  return maximum;
}

void clearFrameStateLocked() {
  gState.frameOpen = false;
  gState.acquiredThisFrame = false;
  gState.acquireResult = XR_ENVIRONMENT_DEPTH_NOT_AVAILABLE_META;
  gState.acquiredSwapchainIndex = 0;
  gState.applicationGpuAttemptedThisFrame = false;
  gState.applicationGpuSubmittedThisFrame = false;
  gState.applicationGpuDroppedThisFrame = false;
  gState.applicationGpuResult = VK_SUCCESS;
}

void clearApplicationDepthSessionStateLocked() {
  gState.applicationDepthProvider = nullptr;
  gState.applicationDepthSwapchain = nullptr;
  gState.applicationAcquireOrdinal = 0;
  gState.applicationDepthCreateAttempted = false;
  gState.applicationDepthStarted = false;
  gState.applicationDepthWidth = 0;
  gState.applicationDepthHeight = 0;
  gState.applicationDepthImages.clear();
  gState.retainedDepthMetadata = {};
  gState.applicationGpuSubmittedGeneration = 0;
  gState.applicationGpuReadyGeneration = 0;
  gState.applicationGpuReadyFrameOrdinal = 0;
  gState.applicationGpuCopyNanoseconds = 0;
  gState.applicationGpuConsumerNanoseconds = 0;
  gState.applicationGpuTotalNanoseconds = 0;
  gState.applicationGpuEnqueueCpuNanoseconds = 0;
  gState.applicationGpuSubmitCpuNanoseconds = 0;
  gState.applicationGpuPollCpuNanoseconds = 0;
  gState.applicationGpuReadyRingIndex = 0;
  gState.applicationGpuStaleFrameCount = 0;
  gState.applicationGpuDiagnosticMinimum = 0.0f;
  gState.applicationGpuDiagnosticMaximum = 0.0f;
  gState.applicationGpuSampleable = false;
  gState.applicationGpuFragmentSampleEvidence = false;
  gState.renderViewDisplayTime = 0;
  gState.renderViewSpace = nullptr;
  gState.renderViewConfigurationType = 0;
  gState.renderViewSessionOrdinal = 0;
  gState.renderViewInterceptOrdinal = 0;
  gState.renderViewStateFlags = 0;
  gState.renderViews = {};
  gState.renderViewCount = 0;
  gState.previousDirectRenderViews = {};
  gState.directRenderViewLocateOrdinal = 0;
  gState.directRenderViewPreviousValid = false;
  gState.movingHeadObserved = false;
  gState.nextSpaceOrdinal = 1;
  gState.liveSpaceOrdinals.clear();
  gState.destroyedSpaces.clear();
  clearFrameStateLocked();
}

void configureApplicationDepthCopyLocked(const char* trigger) {
  if (gDepthGpuHandoff.isConfigured() || gState.applicationDepthWidth == 0 ||
      gState.applicationDepthHeight == 0 || gState.applicationDepthImages.empty()) {
    return;
  }
  const DepthGpuConfigureOutcome outcome = gDepthGpuHandoff.configure(
      gState.applicationDepthWidth,
      gState.applicationDepthHeight,
      gState.applicationDepthImages);
  marker(
      "channel=gpu-handoff status=%s trigger=%s stage=%s vkResult=%d width=%u height=%u imageCount=%u ringSize=3 sourceFormat=VK_FORMAT_D16_UNORM retainedFormat=VK_FORMAT_D16_UNORM sourceLayoutAssumption=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL copyPath=depth-raster-copy consumerPath=independent-fragment-pass perFrameHostFenceWait=false deviceLocalD16Supported=%s timestampSupported=%s timestampValidBits=%u timestampPeriodNs=%.6f ahardwareBufferFunctionsCallable=%s externalSemaphoreFdFunctionsCallable=%s externalInteropAttempted=false externalInteropReason=same-device-path-selected",
      outcome.configured ? "configured" : "configure-failed",
      trigger,
      outcome.stage,
      outcome.result,
      gState.applicationDepthWidth,
      gState.applicationDepthHeight,
      static_cast<uint32_t>(gState.applicationDepthImages.size()),
      outcome.deviceLocalD16Supported ? "true" : "false",
      outcome.timestampSupported ? "true" : "false",
      outcome.timestampValidBits,
      outcome.timestampPeriodNanoseconds,
      outcome.ahardwareBufferFunctionsCallable ? "true" : "false",
      outcome.externalSemaphoreFdFunctionsCallable ? "true" : "false");
}

bool recordApplicationDepthFunctionResolutionLocked(uint32_t bit) {
  const bool firstResolution = (gState.applicationDepthFunctionMask & bit) == 0;
  gState.applicationDepthFunctionMask |= bit;
  return firstResolution;
}

void cleanupDepthLocked(bool clearFrameState = true) {
  if (gState.depthStarted && gState.downstream.stopEnvironmentDepthProvider != nullptr) {
    const XrResult result = gState.downstream.stopEnvironmentDepthProvider(gState.depthProvider);
    marker("channel=depth status=provider-stop result=%d sessionOrdinal=%llu",
           result,
           static_cast<unsigned long long>(gState.sessionOrdinal));
  }
  gState.depthStarted = false;
  if (gState.depthSwapchain != nullptr &&
      gState.downstream.destroyEnvironmentDepthSwapchain != nullptr) {
    gState.downstream.destroyEnvironmentDepthSwapchain(gState.depthSwapchain);
  }
  if (gState.depthProvider != nullptr &&
      gState.downstream.destroyEnvironmentDepthProvider != nullptr) {
    gState.downstream.destroyEnvironmentDepthProvider(gState.depthProvider);
  }
  if (gState.localSpace != nullptr && gState.downstream.destroySpace != nullptr) {
    gState.downstream.destroySpace(gState.localSpace);
  }
  gState.depthSwapchain = nullptr;
  gState.depthProvider = nullptr;
  gState.localSpace = nullptr;
  gState.predictedDisplayTime = 0;
  if (clearFrameState) {
    clearFrameStateLocked();
  }
}

bool depthFunctionsCallable(const Dispatch& dispatch) {
  return dispatch.createEnvironmentDepthProvider != nullptr &&
         dispatch.destroyEnvironmentDepthProvider != nullptr &&
         dispatch.startEnvironmentDepthProvider != nullptr &&
         dispatch.stopEnvironmentDepthProvider != nullptr &&
         dispatch.createEnvironmentDepthSwapchain != nullptr &&
         dispatch.destroyEnvironmentDepthSwapchain != nullptr &&
         dispatch.enumerateEnvironmentDepthSwapchainImages != nullptr &&
         dispatch.getEnvironmentDepthSwapchainState != nullptr &&
         dispatch.acquireEnvironmentDepthImage != nullptr;
}

void resolveDispatchLocked(PFN_xrGetInstanceProcAddr nextGetInstanceProcAddr, XrInstance instance) {
  Dispatch dispatch{};
  dispatch.getInstanceProcAddr = nextGetInstanceProcAddr;
  dispatch.destroyInstance = loadFunction<PFN_xrDestroyInstance>(nextGetInstanceProcAddr, instance, "xrDestroyInstance");
  dispatch.createVulkanInstance = loadFunction<PFN_xrCreateVulkanInstanceKHR>(
      nextGetInstanceProcAddr, instance, "xrCreateVulkanInstanceKHR");
  dispatch.createVulkanDevice = loadFunction<PFN_xrCreateVulkanDeviceKHR>(
      nextGetInstanceProcAddr, instance, "xrCreateVulkanDeviceKHR");
  dispatch.createSession = loadFunction<PFN_xrCreateSession>(nextGetInstanceProcAddr, instance, "xrCreateSession");
  dispatch.destroySession = loadFunction<PFN_xrDestroySession>(nextGetInstanceProcAddr, instance, "xrDestroySession");
  dispatch.createReferenceSpace = loadFunction<PFN_xrCreateReferenceSpace>(nextGetInstanceProcAddr, instance, "xrCreateReferenceSpace");
  dispatch.destroySpace = loadFunction<PFN_xrDestroySpace>(nextGetInstanceProcAddr, instance, "xrDestroySpace");
  dispatch.locateViews = loadFunction<PFN_xrLocateViews>(
      nextGetInstanceProcAddr, instance, "xrLocateViews");
  dispatch.waitFrame = loadFunction<PFN_xrWaitFrame>(nextGetInstanceProcAddr, instance, "xrWaitFrame");
  dispatch.beginFrame = loadFunction<PFN_xrBeginFrame>(nextGetInstanceProcAddr, instance, "xrBeginFrame");
  dispatch.endFrame = loadFunction<PFN_xrEndFrame>(nextGetInstanceProcAddr, instance, "xrEndFrame");
  dispatch.createEnvironmentDepthProvider = loadFunction<PFN_xrCreateEnvironmentDepthProviderMETA>(
      nextGetInstanceProcAddr, instance, "xrCreateEnvironmentDepthProviderMETA");
  dispatch.destroyEnvironmentDepthProvider = loadFunction<PFN_xrDestroyEnvironmentDepthProviderMETA>(
      nextGetInstanceProcAddr, instance, "xrDestroyEnvironmentDepthProviderMETA");
  dispatch.startEnvironmentDepthProvider = loadFunction<PFN_xrStartEnvironmentDepthProviderMETA>(
      nextGetInstanceProcAddr, instance, "xrStartEnvironmentDepthProviderMETA");
  dispatch.stopEnvironmentDepthProvider = loadFunction<PFN_xrStopEnvironmentDepthProviderMETA>(
      nextGetInstanceProcAddr, instance, "xrStopEnvironmentDepthProviderMETA");
  dispatch.createEnvironmentDepthSwapchain = loadFunction<PFN_xrCreateEnvironmentDepthSwapchainMETA>(
      nextGetInstanceProcAddr, instance, "xrCreateEnvironmentDepthSwapchainMETA");
  dispatch.destroyEnvironmentDepthSwapchain = loadFunction<PFN_xrDestroyEnvironmentDepthSwapchainMETA>(
      nextGetInstanceProcAddr, instance, "xrDestroyEnvironmentDepthSwapchainMETA");
  dispatch.enumerateEnvironmentDepthSwapchainImages =
      loadFunction<PFN_xrEnumerateEnvironmentDepthSwapchainImagesMETA>(
          nextGetInstanceProcAddr, instance, "xrEnumerateEnvironmentDepthSwapchainImagesMETA");
  dispatch.getEnvironmentDepthSwapchainState = loadFunction<PFN_xrGetEnvironmentDepthSwapchainStateMETA>(
      nextGetInstanceProcAddr, instance, "xrGetEnvironmentDepthSwapchainStateMETA");
  dispatch.acquireEnvironmentDepthImage = loadFunction<PFN_xrAcquireEnvironmentDepthImageMETA>(
      nextGetInstanceProcAddr, instance, "xrAcquireEnvironmentDepthImageMETA");
  gState.downstream = dispatch;
  gState.callableDepthFunctions = depthFunctionsCallable(dispatch);
}

void setupDepthLocked() {
  if (gState.depthSetupAttempted) {
    return;
  }
  gState.depthSetupAttempted = true;
  if (!kLayerOwnedDepthAcquisitionEnabled) {
    marker("channel=depth status=setup-skipped reason=observation-only layerOwnedAcquireEnabled=false applicationDepthCreateAttempted=%s applicationDepthProviderObserved=%s",
           gState.applicationDepthCreateAttempted ? "true" : "false",
           gState.applicationDepthProvider != nullptr ? "true" : "false");
    return;
  }
  if (gState.applicationDepthCreateAttempted || gState.applicationDepthProvider != nullptr) {
    marker("channel=depth status=setup-skipped reason=application-depth-path-observed layerOwnedAcquireEnabled=true applicationDepthCreateAttempted=%s applicationDepthProviderObserved=%s",
           gState.applicationDepthCreateAttempted ? "true" : "false",
           gState.applicationDepthProvider != nullptr ? "true" : "false");
    return;
  }
  if (!gState.depthExtensionEnabled || !gState.callableDepthFunctions || gState.session == nullptr) {
    marker("channel=depth status=setup-skipped extensionEnabled=%s callableDepthFunctions=%s sessionOrdinal=%llu",
           gState.depthExtensionEnabled ? "true" : "false",
           gState.callableDepthFunctions ? "true" : "false",
           static_cast<unsigned long long>(gState.sessionOrdinal));
    return;
  }

  const XrReferenceSpaceCreateInfo spaceInfo{
      XR_TYPE_REFERENCE_SPACE_CREATE_INFO,
      nullptr,
      XR_REFERENCE_SPACE_TYPE_LOCAL,
      {{0.0F, 0.0F, 0.0F, 1.0F}, {0.0F, 0.0F, 0.0F}}};
  XrResult result = gState.downstream.createReferenceSpace(gState.session, &spaceInfo, &gState.localSpace);
  if (result != XR_SUCCESS) {
    marker("channel=depth status=setup-failed stage=create-reference-space result=%d", result);
    cleanupDepthLocked(false);
    return;
  }

  const XrEnvironmentDepthProviderCreateInfoMETA providerInfo{
      XR_TYPE_ENVIRONMENT_DEPTH_PROVIDER_CREATE_INFO_META, nullptr, 0};
  result = gState.downstream.createEnvironmentDepthProvider(
      gState.session, &providerInfo, &gState.depthProvider);
  if (result != XR_SUCCESS) {
    marker("channel=depth status=setup-failed stage=create-provider result=%d", result);
    cleanupDepthLocked(false);
    return;
  }

  const XrEnvironmentDepthSwapchainCreateInfoMETA swapchainInfo{
      XR_TYPE_ENVIRONMENT_DEPTH_SWAPCHAIN_CREATE_INFO_META, nullptr, 0};
  result = gState.downstream.createEnvironmentDepthSwapchain(
      gState.depthProvider, &swapchainInfo, &gState.depthSwapchain);
  if (result != XR_SUCCESS) {
    marker("channel=depth status=setup-failed stage=create-swapchain result=%d", result);
    cleanupDepthLocked(false);
    return;
  }

  XrEnvironmentDepthSwapchainStateMETA swapchainState{
      XR_TYPE_ENVIRONMENT_DEPTH_SWAPCHAIN_STATE_META, nullptr, 0, 0};
  const XrResult stateResult =
      gState.downstream.getEnvironmentDepthSwapchainState(gState.depthSwapchain, &swapchainState);
  uint32_t imageCount = 0;
  const XrResult enumerateResult = gState.downstream.enumerateEnvironmentDepthSwapchainImages(
      gState.depthSwapchain, 0, &imageCount, nullptr);
  result = gState.downstream.startEnvironmentDepthProvider(gState.depthProvider);
  if (result != XR_SUCCESS) {
    marker("channel=depth status=setup-failed stage=start-provider result=%d stateResult=%d enumerateResult=%d",
           result,
           stateResult,
           enumerateResult);
    cleanupDepthLocked(false);
    return;
  }
  gState.depthStarted = true;
  marker("channel=depth status=provider-started sessionOrdinal=%llu stateResult=%d width=%u height=%u enumerateResult=%d imageCount=%u productionSidecar=false",
         static_cast<unsigned long long>(gState.sessionOrdinal),
         stateResult,
         swapchainState.width,
         swapchainState.height,
         enumerateResult,
         imageCount);
}

}  // namespace

extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrGetInstanceProcAddr(
    XrInstance instance, const char* name, PFN_xrVoidFunction* function);
extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrCreateApiLayerInstance(
    const XrInstanceCreateInfo* createInfo,
    const XrApiLayerCreateInfo* apiLayerInfo,
    XrInstance* instance);
extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrCreateVulkanInstanceKHR(
    XrInstance instance,
    const XrVulkanInstanceCreateInfoKHR* createInfo,
    VkInstance* vulkanInstance,
    VkResult* vulkanResult);
extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrCreateVulkanDeviceKHR(
    XrInstance instance,
    const XrVulkanDeviceCreateInfoKHR* createInfo,
    VkDevice* vulkanDevice,
    VkResult* vulkanResult);
extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrCreateReferenceSpace(
    XrSession session,
    const XrReferenceSpaceCreateInfo* createInfo,
    XrSpace* space);
extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrDestroySpace(XrSpace space);

extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrInitializeLoaderKHR(
    const XrLoaderInitInfoBaseHeaderKHR* loaderInitInfo) {
  marker("channel=loader status=initialize-intercepted initInfoPresent=%s",
         loaderInitInfo != nullptr ? "true" : "false");
  return XR_SUCCESS;
}

extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrCreateVulkanInstanceKHR(
    XrInstance instance,
    const XrVulkanInstanceCreateInfoKHR* createInfo,
    VkInstance* vulkanInstance,
    VkResult* vulkanResult) {
  PFN_xrCreateVulkanInstanceKHR downstream = nullptr;
  {
    std::lock_guard lock(gMutex);
    downstream = gState.downstream.createVulkanInstance;
  }
  if (downstream == nullptr) {
    return XR_ERROR_FUNCTION_UNSUPPORTED;
  }
  const VkInstanceCreateInfo* vkInfo = createInfo != nullptr ? createInfo->vulkanCreateInfo : nullptr;
  const uint32_t surfaceRequestCount = vkInfo != nullptr
      ? countRequestedVulkanExtension(
          vkInfo->enabledExtensionCount,
          vkInfo->ppEnabledExtensionNames,
          VK_KHR_SURFACE_EXTENSION_NAME)
      : 0U;
  const uint32_t androidSurfaceRequestCount = vkInfo != nullptr
      ? countRequestedVulkanExtension(
          vkInfo->enabledExtensionCount,
          vkInfo->ppEnabledExtensionNames,
          VK_KHR_ANDROID_SURFACE_EXTENSION_NAME)
      : 0U;
  const bool surfaceRequestedBefore = surfaceRequestCount == 1U;
  const bool androidSurfaceRequestedBefore = androidSurfaceRequestCount == 1U;
  const bool duplicateRequest =
      surfaceRequestCount > 1U || androidSurfaceRequestCount > 1U;
  bool instanceExtensionEnumerationCallable = false;
  const bool surfacePhysicallySupported = createInfo != nullptr &&
      queryInstanceExtensionSupport(
          createInfo->pfnGetInstanceProcAddr,
          VK_KHR_SURFACE_EXTENSION_NAME,
          &instanceExtensionEnumerationCallable);
  bool androidSurfaceEnumerationCallable = false;
  const bool androidSurfacePhysicallySupported = createInfo != nullptr &&
      queryInstanceExtensionSupport(
          createInfo->pfnGetInstanceProcAddr,
          VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
          &androidSurfaceEnumerationCallable);
  VkInstanceCreateInfo forwardedVkInfo{};
  XrVulkanInstanceCreateInfoKHR forwardedXrInfo{};
  if (vkInfo != nullptr) {
    forwardedVkInfo = *vkInfo;
  }
  if (createInfo != nullptr) {
    forwardedXrInfo = *createInfo;
  }
  std::vector<const char*> forwardedExtensionNames;
  const bool surfaceAugmented =
      vkInfo != nullptr && surfacePhysicallySupported && surfaceRequestCount == 0U;
  const bool androidSurfaceAugmented =
      vkInfo != nullptr && androidSurfacePhysicallySupported &&
      androidSurfaceRequestCount == 0U;
  if (vkInfo != nullptr && (surfaceAugmented || androidSurfaceAugmented)) {
    forwardedExtensionNames.reserve(
        vkInfo->enabledExtensionCount +
        static_cast<uint32_t>(surfaceAugmented) +
        static_cast<uint32_t>(androidSurfaceAugmented));
    for (uint32_t index = 0; index < vkInfo->enabledExtensionCount; ++index) {
      forwardedExtensionNames.push_back(vkInfo->ppEnabledExtensionNames[index]);
    }
    if (surfaceAugmented) {
      forwardedExtensionNames.push_back(VK_KHR_SURFACE_EXTENSION_NAME);
    }
    if (androidSurfaceAugmented) {
      forwardedExtensionNames.push_back(VK_KHR_ANDROID_SURFACE_EXTENSION_NAME);
    }
    forwardedVkInfo.enabledExtensionCount =
        static_cast<uint32_t>(forwardedExtensionNames.size());
    forwardedVkInfo.ppEnabledExtensionNames = forwardedExtensionNames.data();
    forwardedXrInfo.vulkanCreateInfo = &forwardedVkInfo;
  }
  const bool surfaceForwarded = !duplicateRequest &&
      (surfaceRequestedBefore || surfaceAugmented);
  const bool androidSurfaceForwarded = !duplicateRequest &&
      (androidSurfaceRequestedBefore || androidSurfaceAugmented);
  marker(
      "channel=vulkan-create status=instance-extension-decision surfaceSupported=%s androidSurfaceSupported=%s enumerationCallable=%s requestedBeforeSurface=%s requestedBeforeAndroidSurface=%s surfaceRequestCount=%u androidSurfaceRequestCount=%u duplicateRejected=%s augmentedSurface=%s augmentedAndroidSurface=%s forwardedAfterSurface=%s forwardedAfterAndroidSurface=%s forwardedStorageLifetime=through-downstream-xrCreateVulkanInstanceKHR-call failClosed=%s",
      surfacePhysicallySupported ? "true" : "false",
      androidSurfacePhysicallySupported ? "true" : "false",
      instanceExtensionEnumerationCallable && androidSurfaceEnumerationCallable
          ? "true"
          : "false",
      surfaceRequestedBefore ? "true" : "false",
      androidSurfaceRequestedBefore ? "true" : "false",
      surfaceRequestCount,
      androidSurfaceRequestCount,
      duplicateRequest ? "true" : "false",
      surfaceAugmented ? "true" : "false",
      androidSurfaceAugmented ? "true" : "false",
      surfaceForwarded ? "true" : "false",
      androidSurfaceForwarded ? "true" : "false",
      duplicateRequest || !surfaceForwarded || !androidSurfaceForwarded
          ? "true"
          : "false");
  if (duplicateRequest) {
    if (vulkanResult != nullptr) {
      *vulkanResult = VK_ERROR_INITIALIZATION_FAILED;
    }
    return XR_ERROR_VALIDATION_FAILURE;
  }
  const XrVulkanInstanceCreateInfoKHR* downstreamCreateInfo =
      surfaceAugmented || androidSurfaceAugmented ? &forwardedXrInfo : createInfo;
  const XrResult result =
      downstream(instance, downstreamCreateInfo, vulkanInstance, vulkanResult);
  const bool createSucceeded =
      result == XR_SUCCESS &&
      (vulkanResult == nullptr || *vulkanResult == VK_SUCCESS);
  {
    std::lock_guard lock(gMutex);
    if (instance == gState.instance) {
      gState.vulkanInstanceCreateObserved = true;
      gState.vulkanInstanceSurfaceRequested = surfaceForwarded && createSucceeded;
      gState.vulkanInstanceAndroidSurfaceRequested =
          androidSurfaceForwarded && createSucceeded;
      if (result == XR_SUCCESS && vulkanInstance != nullptr) {
        gState.vulkanInstance = *vulkanInstance;
      }
    }
  }
  marker(
      "channel=vulkan-create status=instance-forwarded exactXrInstance=%s xrResult=%d vkResult=%d requestedExtensionCount=%u forwardedExtensionCount=%u khrSurfaceRequestedBefore=%s khrAndroidSurfaceRequestedBefore=%s khrSurfaceForwardedAfter=%s khrAndroidSurfaceForwardedAfter=%s khrSurfaceEffectiveEnabled=%s khrAndroidSurfaceEffectiveEnabled=%s enabledRequestObserved=true forwardedStorageLifetime=through-downstream-xrCreateVulkanInstanceKHR-call downstreamCallReturned=true",
      instance == gState.instance ? "true" : "false",
      result,
      vulkanResult != nullptr ? *vulkanResult : VK_ERROR_UNKNOWN,
      vkInfo != nullptr ? vkInfo->enabledExtensionCount : 0U,
      vkInfo != nullptr
          ? vkInfo->enabledExtensionCount +
                static_cast<uint32_t>(surfaceAugmented) +
                static_cast<uint32_t>(androidSurfaceAugmented)
          : 0U,
      surfaceRequestedBefore ? "true" : "false",
      androidSurfaceRequestedBefore ? "true" : "false",
      surfaceForwarded ? "true" : "false",
      androidSurfaceForwarded ? "true" : "false",
      surfaceForwarded && createSucceeded ? "true" : "false",
      androidSurfaceForwarded && createSucceeded ? "true" : "false");
  return result;
}

extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrCreateVulkanDeviceKHR(
    XrInstance instance,
    const XrVulkanDeviceCreateInfoKHR* createInfo,
    VkDevice* vulkanDevice,
    VkResult* vulkanResult) {
  PFN_xrCreateVulkanDeviceKHR downstream = nullptr;
  {
    std::lock_guard lock(gMutex);
    downstream = gState.downstream.createVulkanDevice;
  }
  if (downstream == nullptr) {
    return XR_ERROR_FUNCTION_UNSUPPORTED;
  }
  const VkDeviceCreateInfo* vkInfo = createInfo != nullptr ? createInfo->vulkanCreateInfo : nullptr;
  const uint32_t swapchainRequestCount = vkInfo != nullptr
      ? countRequestedVulkanExtension(
          vkInfo->enabledExtensionCount,
          vkInfo->ppEnabledExtensionNames,
          VK_KHR_SWAPCHAIN_EXTENSION_NAME)
      : 0U;
  const uint32_t ahbRequestCount = vkInfo != nullptr
      ? countRequestedVulkanExtension(
          vkInfo->enabledExtensionCount,
          vkInfo->ppEnabledExtensionNames,
          VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME)
      : 0U;
  const uint32_t ycbcrExtensionRequestCount = vkInfo != nullptr
      ? countRequestedVulkanExtension(
          vkInfo->enabledExtensionCount,
          vkInfo->ppEnabledExtensionNames,
          VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME)
      : 0U;
  const bool swapchainRequestedBefore = swapchainRequestCount == 1U;
  const bool ahbRequestedBefore = ahbRequestCount > 0U;
  const bool ycbcrExtensionRequestedBefore = ycbcrExtensionRequestCount == 1U;
  const uint32_t externalSemaphoreFdRequestCount = vkInfo != nullptr
      ? countRequestedVulkanExtension(
          vkInfo->enabledExtensionCount,
          vkInfo->ppEnabledExtensionNames,
          VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME)
      : 0U;
  const bool externalSemaphoreFdPresentBefore =
      externalSemaphoreFdRequestCount > 0U;
  const bool externalSemaphoreFdRequested =
      externalSemaphoreFdRequestCount > 0U;
  const YcbcrFeatureRequestInspection featureInspection =
      inspectSamplerYcbcrFeatureRequest(vkInfo);
  VkInstance observedVulkanInstance = VK_NULL_HANDLE;
  {
    std::lock_guard lock(gMutex);
    observedVulkanInstance = gState.vulkanInstance;
  }
  bool features2Callable = false;
  const bool physicallySupported = createInfo != nullptr && querySamplerYcbcrPhysicalSupport(
      observedVulkanInstance,
      createInfo->vulkanPhysicalDevice,
      createInfo->pfnGetInstanceProcAddr,
      &features2Callable);
  bool extensionEnumerationCallable = false;
  const bool externalSemaphoreFdPhysicallySupported =
      createInfo != nullptr && queryDeviceExtensionSupport(
          observedVulkanInstance,
          createInfo->vulkanPhysicalDevice,
          createInfo->pfnGetInstanceProcAddr,
          VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME,
          &extensionEnumerationCallable);
  bool swapchainEnumerationCallable = false;
  const bool swapchainPhysicallySupported =
      createInfo != nullptr && queryDeviceExtensionSupport(
          observedVulkanInstance,
          createInfo->vulkanPhysicalDevice,
          createInfo->pfnGetInstanceProcAddr,
          VK_KHR_SWAPCHAIN_EXTENSION_NAME,
          &swapchainEnumerationCallable);
  bool ahbEnumerationCallable = false;
  const bool ahbPhysicallySupported =
      createInfo != nullptr && queryDeviceExtensionSupport(
          observedVulkanInstance,
          createInfo->vulkanPhysicalDevice,
          createInfo->pfnGetInstanceProcAddr,
          VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
          &ahbEnumerationCallable);
  bool ycbcrExtensionEnumerationCallable = false;
  const bool ycbcrExtensionPhysicallySupported =
      createInfo != nullptr && queryDeviceExtensionSupport(
          observedVulkanInstance,
          createInfo->vulkanPhysicalDevice,
          createInfo->pfnGetInstanceProcAddr,
          VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME,
          &ycbcrExtensionEnumerationCallable);

  VkPhysicalDeviceSamplerYcbcrConversionFeatures duplicateDedicatedFeatures{};
  duplicateDedicatedFeatures.sType =
      VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES;
  VkPhysicalDeviceVulkan11Features duplicateVulkan11Features{};
  duplicateVulkan11Features.sType =
      VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES;
  duplicateVulkan11Features.pNext = &duplicateDedicatedFeatures;
  VkDeviceCreateInfo duplicateDeviceCreateInfo{};
  duplicateDeviceCreateInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
  duplicateDeviceCreateInfo.pNext = &duplicateVulkan11Features;
  const YcbcrFeatureRequestInspection duplicateInspection =
      inspectSamplerYcbcrFeatureRequest(&duplicateDeviceCreateInfo);
  const bool duplicateChainRejected =
      duplicateInspection.source == YcbcrFeatureStructSource::kIllegalDuplicate;
  const std::array<const char*, 2> duplicateExtensionNames{{
      VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME,
      VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME}};
  const bool duplicateExtensionSelfTestRejected =
      countRequestedVulkanExtension(
          static_cast<uint32_t>(duplicateExtensionNames.size()),
          duplicateExtensionNames.data(),
          VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME) > 1U;
  marker(
      "channel=vulkan-create status=feature-chain-self-test duplicateStructChainDetected=%s duplicateChainRejected=%s duplicateExternalSemaphoreFdDetected=true duplicateExternalSemaphoreFdRejected=%s illegalDuplicateWouldAugment=false",
      duplicateInspection.dedicatedPresent && duplicateInspection.vulkan11Present
          ? "true"
          : "false",
      duplicateChainRejected ? "true" : "false",
      duplicateExtensionSelfTestRejected ? "true" : "false");

  VkDeviceCreateInfo forwardedVkInfo{};
  XrVulkanDeviceCreateInfoKHR forwardedXrInfo{};
  if (vkInfo != nullptr) {
    forwardedVkInfo = *vkInfo;
  }
  if (createInfo != nullptr) {
    forwardedXrInfo = *createInfo;
  }
  std::vector<const char*> forwardedExtensionNames;
  VkPhysicalDeviceSamplerYcbcrConversionFeatures forwardedDedicatedFeatures{};
  forwardedDedicatedFeatures.sType =
      VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES;
  VkPhysicalDeviceVulkan11Features forwardedVulkan11Features{};
  forwardedVulkan11Features.sType =
      VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES;
  const XrVulkanDeviceCreateInfoKHR* downstreamCreateInfo = createInfo;
  bool malformedExtensionName = false;
  bool upstreamDuplicateObserved = false;
  uint32_t duplicateElidedCount = 0U;
  if (vkInfo != nullptr) {
    if (vkInfo->enabledExtensionCount > 0U &&
        vkInfo->ppEnabledExtensionNames == nullptr) {
      malformedExtensionName = true;
    } else {
      forwardedExtensionNames.reserve(vkInfo->enabledExtensionCount + 4U);
      for (uint32_t index = 0; index < vkInfo->enabledExtensionCount; ++index) {
        const char* extensionName = vkInfo->ppEnabledExtensionNames[index];
        if (extensionName == nullptr || extensionName[0] == '\0') {
          malformedExtensionName = true;
          break;
        }
        bool alreadyPresent = false;
        for (const char* forwardedName : forwardedExtensionNames) {
          if (std::strcmp(forwardedName, extensionName) == 0) {
            alreadyPresent = true;
            break;
          }
        }
        if (alreadyPresent) {
          upstreamDuplicateObserved = true;
          ++duplicateElidedCount;
          continue;
        }
        forwardedExtensionNames.push_back(extensionName);
      }
    }
  }
  const auto forwardedHasExtension = [&forwardedExtensionNames](const char* name) {
    for (const char* forwardedName : forwardedExtensionNames) {
      if (std::strcmp(forwardedName, name) == 0) {
        return true;
      }
    }
    return false;
  };
  bool augmentationAttempted = false;
  bool augmentationLegal = false;
  bool augmented = false;
  if (createInfo != nullptr && vkInfo != nullptr && physicallySupported &&
      !featureInspection.requested) {
    augmentationAttempted = true;
    if (featureInspection.source == YcbcrFeatureStructSource::kNone) {
      forwardedDedicatedFeatures.pNext = const_cast<void*>(vkInfo->pNext);
      forwardedDedicatedFeatures.samplerYcbcrConversion = VK_TRUE;
      forwardedVkInfo.pNext = &forwardedDedicatedFeatures;
      augmentationLegal = true;
    } else if (featureInspection.source == YcbcrFeatureStructSource::kDedicated &&
               featureInspection.targetAtChainHead) {
      const auto* incoming =
          static_cast<const VkPhysicalDeviceSamplerYcbcrConversionFeatures*>(vkInfo->pNext);
      forwardedDedicatedFeatures = *incoming;
      forwardedDedicatedFeatures.samplerYcbcrConversion = VK_TRUE;
      forwardedVkInfo.pNext = &forwardedDedicatedFeatures;
      augmentationLegal = true;
    } else if (featureInspection.source == YcbcrFeatureStructSource::kVulkan11 &&
               featureInspection.targetAtChainHead) {
      const auto* incoming =
          static_cast<const VkPhysicalDeviceVulkan11Features*>(vkInfo->pNext);
      forwardedVulkan11Features = *incoming;
      forwardedVulkan11Features.samplerYcbcrConversion = VK_TRUE;
      forwardedVkInfo.pNext = &forwardedVulkan11Features;
      augmentationLegal = true;
    }
    augmented = augmentationLegal;
  }
  const bool swapchainAugmented =
      vkInfo != nullptr && swapchainPhysicallySupported && swapchainRequestCount == 0U;
  const bool ahbAugmented =
      vkInfo != nullptr && ahbPhysicallySupported && ahbRequestCount == 0U;
  const bool ycbcrExtensionAugmented =
      vkInfo != nullptr && ycbcrExtensionPhysicallySupported &&
      ycbcrExtensionRequestCount == 0U;
  const bool externalSemaphoreFdAugmentationAttempted =
      createInfo != nullptr && vkInfo != nullptr &&
      externalSemaphoreFdPhysicallySupported &&
      externalSemaphoreFdRequestCount == 0U;
  bool externalSemaphoreFdAugmented = false;
  if (!malformedExtensionName && vkInfo != nullptr) {
    if (swapchainAugmented &&
        !forwardedHasExtension(VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
      forwardedExtensionNames.push_back(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
    }
    if (ahbAugmented && !forwardedHasExtension(
            VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME)) {
      forwardedExtensionNames.push_back(
          VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME);
    }
    if (ycbcrExtensionAugmented &&
        !forwardedHasExtension(VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME)) {
      forwardedExtensionNames.push_back(VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME);
    }
    if (externalSemaphoreFdAugmentationAttempted &&
        !forwardedHasExtension(VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME)) {
      forwardedExtensionNames.push_back(VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME);
      externalSemaphoreFdAugmented = true;
    }
    forwardedVkInfo.enabledExtensionCount =
        static_cast<uint32_t>(forwardedExtensionNames.size());
    forwardedVkInfo.ppEnabledExtensionNames = forwardedExtensionNames.data();
  }
  if (vkInfo != nullptr && !malformedExtensionName) {
    forwardedXrInfo.vulkanCreateInfo = &forwardedVkInfo;
    downstreamCreateInfo = &forwardedXrInfo;
  }
  const bool ycbcrFeatureForwarded = featureInspection.requested || augmented;
  const bool externalSemaphoreFdForwarded =
      (externalSemaphoreFdRequested || externalSemaphoreFdAugmented);
  uint32_t finalDuplicateCount = 0U;
  for (size_t left = 0; left < forwardedExtensionNames.size(); ++left) {
    for (size_t right = left + 1U; right < forwardedExtensionNames.size(); ++right) {
      if (std::strcmp(forwardedExtensionNames[left], forwardedExtensionNames[right]) == 0) {
        ++finalDuplicateCount;
      }
    }
  }
  const bool finalExtensionSetUnique = finalDuplicateCount == 0U;
  const bool requiredCapabilitiesSupported =
      swapchainPhysicallySupported && ahbPhysicallySupported &&
      ycbcrExtensionPhysicallySupported && externalSemaphoreFdPhysicallySupported &&
      physicallySupported;
  const bool illegalFeatureChain =
      featureInspection.source == YcbcrFeatureStructSource::kIllegalDuplicate ||
      (augmentationAttempted && !augmentationLegal);
  const bool preflightFailClosed = malformedExtensionName ||
      !finalExtensionSetUnique || !requiredCapabilitiesSupported || illegalFeatureChain;
  const bool swapchainForwarded = !preflightFailClosed &&
      (swapchainRequestedBefore || swapchainAugmented);
  const bool ahbForwarded = !preflightFailClosed &&
      (ahbRequestedBefore || ahbAugmented);
  const bool ycbcrExtensionForwarded = !preflightFailClosed &&
      (ycbcrExtensionRequestedBefore || ycbcrExtensionAugmented);
  const uint32_t forwardedAhbCount = countRequestedVulkanExtension(
      static_cast<uint32_t>(forwardedExtensionNames.size()),
      forwardedExtensionNames.data(),
      VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME);
  marker(
      "channel=vulkan-create status=device-production-extension-decision swapchainSupported=%s ahbSupported=%s ycbcrExtensionSupported=%s enumerationCallable=%s swapchainRequestedBefore=%s ahbRequestedBefore=%s ycbcrExtensionRequestedBefore=%s originalExtensionCount=%u forwardedExtensionCount=%u swapchainRequestCount=%u ahbRequestCount=%u forwardedAhbCount=%u ycbcrExtensionRequestCount=%u upstreamDuplicateObserved=%s duplicateElided=%s duplicateElidedCount=%u exactCaseSensitive=true firstOccurrenceOrderPreserved=true finalExtensionSetUnique=%s malformedExtensionName=%s swapchainAugmented=%s ahbAugmented=%s ycbcrExtensionAugmented=%s swapchainForwardedAfter=%s ahbForwardedAfter=%s ycbcrExtensionForwardedAfter=%s forwardedStorageLifetime=through-downstream-xrCreateVulkanDeviceKHR-call failClosed=%s",
      swapchainPhysicallySupported ? "true" : "false",
      ahbPhysicallySupported ? "true" : "false",
      ycbcrExtensionPhysicallySupported ? "true" : "false",
      swapchainEnumerationCallable && ahbEnumerationCallable &&
              ycbcrExtensionEnumerationCallable
          ? "true"
          : "false",
      swapchainRequestedBefore ? "true" : "false",
      ahbRequestedBefore ? "true" : "false",
      ycbcrExtensionRequestedBefore ? "true" : "false",
      vkInfo != nullptr ? vkInfo->enabledExtensionCount : 0U,
      static_cast<uint32_t>(forwardedExtensionNames.size()),
      swapchainRequestCount,
      ahbRequestCount,
      forwardedAhbCount,
      ycbcrExtensionRequestCount,
      upstreamDuplicateObserved ? "true" : "false",
      upstreamDuplicateObserved ? "true" : "false",
      duplicateElidedCount,
      finalExtensionSetUnique ? "true" : "false",
      malformedExtensionName ? "true" : "false",
      swapchainAugmented ? "true" : "false",
      ahbAugmented ? "true" : "false",
      ycbcrExtensionAugmented ? "true" : "false",
      swapchainForwarded ? "true" : "false",
      ahbForwarded ? "true" : "false",
      ycbcrExtensionForwarded ? "true" : "false",
      preflightFailClosed || !swapchainForwarded || !ahbForwarded ||
              !ycbcrExtensionForwarded
          ? "true"
          : "false");
  marker(
      "channel=vulkan-create status=device-extension-decision extension=VK_KHR_external_semaphore_fd enumeratedSupported=%s enumerationCallable=%s requestedBefore=%s validSingleRequestBefore=%s requestCount=%u duplicateRejected=%s duplicateSelfTestRejected=%s augmentationAttempted=%s augmentationLegal=%s forwardedAfter=%s failClosed=%s semaphoreType=binary handleType=VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT acquireImport=temporary fdOwnership=producer-until-enqueue-layer-until-successful-import-vulkan-on-success-layer-closes-on-failure releaseExport=one-shot-payload-transfer-reset-before-slot-reuse forwardedStorageLifetime=through-downstream-xrCreateVulkanDeviceKHR-call",
      externalSemaphoreFdPhysicallySupported ? "true" : "false",
      extensionEnumerationCallable ? "true" : "false",
      externalSemaphoreFdPresentBefore ? "true" : "false",
      externalSemaphoreFdRequested ? "true" : "false",
      externalSemaphoreFdRequestCount,
      externalSemaphoreFdRequestCount > 1U ? "true" : "false",
      duplicateExtensionSelfTestRejected ? "true" : "false",
      externalSemaphoreFdAugmentationAttempted ? "true" : "false",
      externalSemaphoreFdAugmented ? "true" : "false",
      externalSemaphoreFdForwarded ? "true" : "false",
      (externalSemaphoreFdAugmentationAttempted && !externalSemaphoreFdAugmented) ||
              preflightFailClosed
          ? "true"
          : "false");
  marker(
      "channel=vulkan-create status=device-feature-decision samplerYcbcrRequestedBefore=%s samplerYcbcrStructSource=%s dedicatedStructPresent=%s vulkan11StructPresent=%s targetAtChainHead=%s features2Callable=%s samplerYcbcrPhysicallySupported=%s augmentationAttempted=%s augmentationLegal=%s forwardedAfter=%s mutuallyExclusiveFeatureStructsPreserved=%s duplicateChainSelfTestRejected=%s failClosed=%s forwardedStorageLifetime=through-downstream-xrCreateVulkanDeviceKHR-call",
      featureInspection.requested ? "true" : "false",
      ycbcrFeatureStructSourceName(featureInspection.source),
      featureInspection.dedicatedPresent ? "true" : "false",
      featureInspection.vulkan11Present ? "true" : "false",
      featureInspection.targetAtChainHead ? "true" : "false",
      features2Callable ? "true" : "false",
      physicallySupported ? "true" : "false",
      augmentationAttempted ? "true" : "false",
      augmentationLegal ? "true" : "false",
      ycbcrFeatureForwarded ? "true" : "false",
      featureInspection.source != YcbcrFeatureStructSource::kIllegalDuplicate ? "true" : "false",
      duplicateChainRejected ? "true" : "false",
      augmentationAttempted && !augmentationLegal ? "true" : "false");
  if (preflightFailClosed) {
    if (vulkanResult != nullptr) {
      *vulkanResult = VK_ERROR_INITIALIZATION_FAILED;
    }
    marker(
        "channel=vulkan-create status=device-create-rejected reason=preflight malformedExtensionName=%s finalExtensionSetUnique=%s requiredCapabilitiesSupported=%s illegalFeatureChain=%s downstreamCalled=false failClosed=true",
        malformedExtensionName ? "true" : "false",
        finalExtensionSetUnique ? "true" : "false",
        requiredCapabilitiesSupported ? "true" : "false",
        illegalFeatureChain ? "true" : "false");
    return XR_ERROR_VALIDATION_FAILURE;
  }
  const XrResult result =
      downstream(instance, downstreamCreateInfo, vulkanDevice, vulkanResult);
  {
    std::lock_guard lock(gMutex);
    if (instance == gState.instance) {
      gState.vulkanDeviceCreateObserved = true;
      const bool createSucceeded =
          result == XR_SUCCESS &&
          (vulkanResult == nullptr || *vulkanResult == VK_SUCCESS);
      gState.vulkanDeviceSwapchainRequested = swapchainForwarded && createSucceeded;
      gState.vulkanDeviceAhbRequested = ahbForwarded && createSucceeded;
      gState.vulkanDeviceYcbcrExtensionRequested =
          ycbcrExtensionForwarded && createSucceeded;
      gState.vulkanDeviceYcbcrFeatureRequestedBefore = featureInspection.requested;
      gState.vulkanDeviceYcbcrFeaturePhysicallySupported = physicallySupported;
      gState.vulkanDeviceYcbcrFeatures2Callable = features2Callable;
      gState.vulkanDeviceYcbcrFeatureAugmentationAttempted = augmentationAttempted;
      gState.vulkanDeviceYcbcrFeatureAugmentationLegal = augmentationLegal;
      gState.vulkanDeviceYcbcrFeatureAugmented = augmented;
      gState.vulkanDeviceYcbcrFeatureStructSource = featureInspection.source;
      gState.vulkanDeviceYcbcrFeatureRequested =
          ycbcrFeatureForwarded && result == XR_SUCCESS &&
          (vulkanResult == nullptr || *vulkanResult == VK_SUCCESS);
      gState.vulkanDeviceExternalSemaphoreFdRequestedBefore =
          externalSemaphoreFdPresentBefore;
      gState.vulkanDeviceExternalSemaphoreFdPhysicallySupported =
          externalSemaphoreFdPhysicallySupported;
      gState.vulkanDeviceExternalSemaphoreFdEnumerationCallable =
          extensionEnumerationCallable;
      gState.vulkanDeviceExternalSemaphoreFdAugmented =
          externalSemaphoreFdAugmented;
      gState.vulkanDeviceExternalSemaphoreFdRequested =
          externalSemaphoreFdForwarded && result == XR_SUCCESS &&
          (vulkanResult == nullptr || *vulkanResult == VK_SUCCESS);
    }
  }
  marker(
      "channel=vulkan-create status=device-forwarded exactXrInstance=%s xrResult=%d vkResult=%d requestedExtensionCount=%u forwardedExtensionCount=%u khrSwapchainRequestedBefore=%s androidAhbRequestedBefore=%s khrSamplerYcbcrRequestedBefore=%s khrSwapchainForwardedAfter=%s androidAhbForwardedAfter=%s khrSamplerYcbcrForwardedAfter=%s productionExtensionsEffectiveEnabled=%s externalSemaphoreFdRequestedBefore=%s externalSemaphoreFdForwardedAfter=%s externalSemaphoreFdEffectiveEnabled=%s samplerYcbcrRequestedBefore=%s samplerYcbcrPhysicallySupported=%s samplerYcbcrForwardedAfter=%s samplerYcbcrEffectiveEnabled=%s samplerYcbcrFeatureRequested=%s featureStructSource=%s augmentationAttempted=%s augmentationLegal=%s augmented=%s enabledRequestObserved=true forwardedStorageLifetime=through-downstream-xrCreateVulkanDeviceKHR-call downstreamCallReturned=true",
      instance == gState.instance ? "true" : "false",
      result,
      vulkanResult != nullptr ? *vulkanResult : VK_ERROR_UNKNOWN,
      vkInfo != nullptr ? vkInfo->enabledExtensionCount : 0U,
      vkInfo != nullptr
          ? vkInfo->enabledExtensionCount +
                static_cast<uint32_t>(swapchainAugmented) +
                static_cast<uint32_t>(ahbAugmented) +
                static_cast<uint32_t>(ycbcrExtensionAugmented) +
                static_cast<uint32_t>(externalSemaphoreFdAugmented)
          : 0U,
      swapchainRequestedBefore ? "true" : "false",
      ahbRequestedBefore ? "true" : "false",
      ycbcrExtensionRequestedBefore ? "true" : "false",
      swapchainForwarded ? "true" : "false",
      ahbForwarded ? "true" : "false",
      ycbcrExtensionForwarded ? "true" : "false",
      swapchainForwarded && ahbForwarded && ycbcrExtensionForwarded &&
              result == XR_SUCCESS &&
              (vulkanResult == nullptr || *vulkanResult == VK_SUCCESS)
          ? "true"
          : "false",
      externalSemaphoreFdPresentBefore ? "true" : "false",
      externalSemaphoreFdForwarded ? "true" : "false",
      externalSemaphoreFdForwarded && result == XR_SUCCESS &&
              (vulkanResult == nullptr || *vulkanResult == VK_SUCCESS)
          ? "true"
          : "false",
      featureInspection.requested ? "true" : "false",
      physicallySupported ? "true" : "false",
      ycbcrFeatureForwarded ? "true" : "false",
      ycbcrFeatureForwarded && result == XR_SUCCESS &&
              (vulkanResult == nullptr || *vulkanResult == VK_SUCCESS)
          ? "true"
          : "false",
      ycbcrFeatureForwarded ? "true" : "false",
      ycbcrFeatureStructSourceName(featureInspection.source),
      augmentationAttempted ? "true" : "false",
      augmentationLegal ? "true" : "false",
      augmented ? "true" : "false");
  return result;
}

extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrCreateSession(
    XrInstance instance, const XrSessionCreateInfo* createInfo, XrSession* session) {
  PFN_xrCreateSession downstreamCreate = nullptr;
  VulkanBindingSnapshot vulkanBinding{};
  bool vulkanBindingObserved = false;
  {
    std::lock_guard lock(gMutex);
    downstreamCreate = gState.downstream.createSession;
  }
  if (createInfo != nullptr) {
    auto* chain = static_cast<const XrBaseInStructure*>(createInfo->next);
    while (chain != nullptr) {
      if (chain->type == XR_TYPE_GRAPHICS_BINDING_VULKAN_KHR) {
        const auto* binding = reinterpret_cast<const XrGraphicsBindingVulkanKHR*>(chain);
        vulkanBinding = {
            binding->instance,
            binding->physicalDevice,
            binding->device,
            binding->queueFamilyIndex,
            binding->queueIndex};
        vulkanBindingObserved = true;
        break;
      }
      chain = chain->next;
    }
  }
  if (downstreamCreate == nullptr) {
    return XR_ERROR_FUNCTION_UNSUPPORTED;
  }
  const XrResult result = downstreamCreate(instance, createInfo, session);
  if (result == XR_SUCCESS && session != nullptr && *session != nullptr) {
    std::lock_guard lock(gMutex);
    if (instance == gState.instance && gState.session == nullptr) {
      clearApplicationDepthSessionStateLocked();
      gState.session = *session;
      const uint64_t previousSessionOrdinal = gState.sessionOrdinal;
      gState.sessionOrdinal = nextProcessStableSessionGeneration();
      gState.depthSetupAttempted = false;
      resetSpatialSubmitBrokerLocked();
      const bool vulkanCopyBound = vulkanBindingObserved &&
                                   gDepthGpuHandoff.bind(
                                       vulkanBinding, gState.sessionOrdinal);
      const bool consumerBridgeBound = vulkanCopyBound && gDepthConsumerBridge.bind(
          vulkanBinding, gState.sessionOrdinal, &gDepthGpuHandoff);
      if (consumerBridgeBound) {
        gState.sdkVulkanBinding = vulkanBinding;
        vkGetDeviceQueue(
            vulkanBinding.device,
            vulkanBinding.queueFamilyIndex,
            vulkanBinding.queueIndex,
            &gState.sdkQueue);
        gState.consumerContextToken =
            gDepthConsumerBridge.deviceToken() ^
            (gState.sessionOrdinal * 0x9e3779b97f4a7c15ULL);
        if (gState.consumerContextToken == 0) {
          gState.consumerContextToken = gState.sessionOrdinal;
        }
      }
      marker("channel=lifecycle status=session-created instanceOrdinal=%llu sessionOrdinal=%llu previousSessionOrdinal=%llu sessionReplacement=%s generationAuthority=layer-process-epoch exactInstance=true downstreamResult=%d",
              static_cast<unsigned long long>(gState.instanceOrdinal),
              static_cast<unsigned long long>(gState.sessionOrdinal),
              static_cast<unsigned long long>(previousSessionOrdinal),
              gState.sessionOrdinal > 1 ? "true" : "false",
              result);
      marker("channel=gpu-handoff status=session-binding-observed vulkanBindingObserved=%s vulkanCopyBound=%s consumerBridgeBound=%s queueBound=%s queueFamilyIndex=%u queueIndex=%u producerDevice=spatial-sdk-exact consumerDevice=spatial-sdk-exact sameLogicalDevice=true consumerModule=rust-dlsym-v2 queueSubmissionOwner=layer-app-submit-broker sdkRuntimeSubmissionAuthority=opaque",
             vulkanBindingObserved ? "true" : "false",
             vulkanCopyBound ? "true" : "false",
             consumerBridgeBound ? "true" : "false",
             gState.sdkQueue != VK_NULL_HANDLE ? "true" : "false",
             vulkanBindingObserved ? vulkanBinding.queueFamilyIndex : 0U,
             vulkanBindingObserved ? vulkanBinding.queueIndex : 0U);
      marker("channel=depth status=ownership-observation-armed trigger=first-successful-forwarded-begin sessionOrdinal=%llu layerOwnedAcquireEnabled=false",
             static_cast<unsigned long long>(gState.sessionOrdinal));
    } else {
      marker("channel=lifecycle status=session-observed exactInstance=%s tracked=false downstreamResult=%d",
             instance == gState.instance ? "true" : "false",
             result);
    }
  }
  return result;
}

extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrDestroySession(XrSession session) {
  PFN_xrDestroySession downstreamDestroy = nullptr;
  uint64_t sessionOrdinal = 0;
  {
    std::lock_guard lock(gMutex);
    downstreamDestroy = gState.downstream.destroySession;
    if (session == gState.session) {
      sessionOrdinal = gState.sessionOrdinal;
      gDepthConsumerBridge.resetSession();
      gDepthGpuHandoff.resetSession();
      resetSpatialSubmitBrokerLocked();
      cleanupDepthLocked();
      clearApplicationDepthSessionStateLocked();
      gState.liveSpaceOrdinals.clear();
      gState.destroyedSpaces.clear();
      gState.session = nullptr;
      gState.sdkVulkanBinding = {};
      gState.sdkQueue = VK_NULL_HANDLE;
      gState.consumerContextToken = 0;
    }
  }
  if (downstreamDestroy == nullptr) {
    return XR_ERROR_FUNCTION_UNSUPPORTED;
  }
  const XrResult result = downstreamDestroy(session);
  marker("channel=lifecycle status=session-destroyed sessionOrdinal=%llu downstreamResult=%d",
         static_cast<unsigned long long>(sessionOrdinal),
         result);
  return result;
}

extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrCreateReferenceSpace(
    XrSession session,
    const XrReferenceSpaceCreateInfo* createInfo,
    XrSpace* space) {
  PFN_xrCreateReferenceSpace downstreamCreate = nullptr;
  bool exactSession = false;
  {
    std::lock_guard lock(gMutex);
    downstreamCreate = gState.downstream.createReferenceSpace;
    exactSession = session == gState.session;
  }
  if (downstreamCreate == nullptr) {
    return XR_ERROR_FUNCTION_UNSUPPORTED;
  }
  const XrResult result = downstreamCreate(session, createInfo, space);
  uint64_t spaceOrdinal = 0;
  uint64_t sessionOrdinal = 0;
  if (result == XR_SUCCESS && exactSession && space != nullptr && *space != nullptr) {
    std::lock_guard lock(gMutex);
    sessionOrdinal = gState.sessionOrdinal;
    spaceOrdinal = gState.nextSpaceOrdinal++;
    gState.liveSpaceOrdinals[*space] = spaceOrdinal;
    gState.destroyedSpaces.erase(*space);
  }
  if (spaceOrdinal <= 3 || result != XR_SUCCESS) {
    marker(
        "channel=space status=reference-created downstreamResult=%d exactSession=%s sessionOrdinal=%llu spaceOrdinal=%llu trackedAlive=%s",
        result,
        exactSession ? "true" : "false",
        static_cast<unsigned long long>(sessionOrdinal),
        static_cast<unsigned long long>(spaceOrdinal),
        spaceOrdinal != 0 ? "true" : "false");
  }
  return result;
}

extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrDestroySpace(XrSpace space) {
  PFN_xrDestroySpace downstreamDestroy = nullptr;
  uint64_t spaceOrdinal = 0;
  bool trackedAlive = false;
  {
    std::lock_guard lock(gMutex);
    downstreamDestroy = gState.downstream.destroySpace;
    const auto iterator = gState.liveSpaceOrdinals.find(space);
    if (iterator != gState.liveSpaceOrdinals.end()) {
      spaceOrdinal = iterator->second;
      trackedAlive = true;
    }
  }
  if (downstreamDestroy == nullptr) {
    return XR_ERROR_FUNCTION_UNSUPPORTED;
  }
  const XrResult result = downstreamDestroy(space);
  bool staleRejected = false;
  if (result == XR_SUCCESS && trackedAlive) {
    std::lock_guard lock(gMutex);
    gState.liveSpaceOrdinals.erase(space);
    gState.destroyedSpaces.insert(space);
    staleRejected = !gState.liveSpaceOrdinals.contains(space) &&
                    gState.destroyedSpaces.contains(space);
  }
  marker(
      "channel=space status=destroyed downstreamResult=%d spaceOrdinal=%llu wasTrackedAlive=%s staleSpaceRejectedByLayer=%s downstreamLocateOnStaleSpace=false",
      result,
      static_cast<unsigned long long>(spaceOrdinal),
      trackedAlive ? "true" : "false",
      staleRejected ? "true" : "false");
  return result;
}

extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrLocateViews(
    XrSession session,
    const XrViewLocateInfo* viewLocateInfo,
    XrViewState* viewState,
    uint32_t viewCapacityInput,
    uint32_t* viewCountOutput,
    XrView* views) {
  PFN_xrLocateViews downstreamLocateViews = nullptr;
  {
    std::lock_guard lock(gMutex);
    downstreamLocateViews = gState.downstream.locateViews;
  }
  if (downstreamLocateViews == nullptr) {
    return XR_ERROR_FUNCTION_UNSUPPORTED;
  }
  const XrResult result = downstreamLocateViews(
      session,
      viewLocateInfo,
      viewState,
      viewCapacityInput,
      viewCountOutput,
      views);
  if (result == XR_SUCCESS && viewLocateInfo != nullptr && viewState != nullptr &&
      viewCountOutput != nullptr && views != nullptr && viewCapacityInput >= 2U &&
      *viewCountOutput >= 2U) {
    std::lock_guard lock(gMutex);
    if (session == gState.session) {
      gState.renderViewDisplayTime = viewLocateInfo->displayTime;
      gState.renderViewSpace = viewLocateInfo->space;
      gState.renderViewConfigurationType = viewLocateInfo->viewConfigurationType;
      gState.renderViewSessionOrdinal = gState.sessionOrdinal;
      ++gState.renderViewInterceptOrdinal;
      gState.renderViewStateFlags = viewState->viewStateFlags;
      gState.renderViews[0] = views[0];
      gState.renderViews[1] = views[1];
      gState.renderViewCount = 2U;
      if (gState.frameOrdinal <= 3U || gState.frameOrdinal % 300U == 0U) {
        marker(
            "channel=render-views status=intercepted frameOrdinal=%llu sessionOrdinal=%llu displayTime=%lld viewConfigurationType=%d viewCount=%u viewStateFlags=%llu spaceTrackedAlive=%s exactSession=true",
            static_cast<unsigned long long>(gState.frameOrdinal),
            static_cast<unsigned long long>(gState.sessionOrdinal),
            static_cast<long long>(gState.renderViewDisplayTime),
            gState.renderViewConfigurationType,
            gState.renderViewCount,
            static_cast<unsigned long long>(gState.renderViewStateFlags),
            gState.liveSpaceOrdinals.contains(gState.renderViewSpace) ? "true" : "false");
      }
    }
  }
  return result;
}

extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrWaitFrame(
    XrSession session, const XrFrameWaitInfo* waitInfo, XrFrameState* frameState) {
  PFN_xrWaitFrame downstreamWait = nullptr;
  {
    std::lock_guard lock(gMutex);
    downstreamWait = gState.downstream.waitFrame;
  }
  if (downstreamWait == nullptr) {
    return XR_ERROR_FUNCTION_UNSUPPORTED;
  }
  const XrResult result = downstreamWait(session, waitInfo, frameState);
  if (result == XR_SUCCESS && frameState != nullptr) {
    std::lock_guard lock(gMutex);
    if (session == gState.session) {
      gState.predictedDisplayTime = frameState->predictedDisplayTime;
    }
  }
  return result;
}

extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrBeginFrame(
    XrSession session, const XrFrameBeginInfo* beginInfo) {
  PFN_xrBeginFrame downstreamBegin = nullptr;
  {
    std::lock_guard lock(gMutex);
    downstreamBegin = gState.downstream.beginFrame;
  }
  if (downstreamBegin == nullptr) {
    return XR_ERROR_FUNCTION_UNSUPPORTED;
  }

  const XrResult beginResult = downstreamBegin(session, beginInfo);
  if (beginResult != XR_SUCCESS) {
    marker("channel=frame status=begin-forwarded downstreamResult=%d acquisitionAttempted=false", beginResult);
    return beginResult;
  }

  std::lock_guard lock(gMutex);
  if (session != gState.session) {
    marker("channel=frame status=begin-forwarded downstreamResult=%d exactSession=false acquisitionAttempted=false",
           beginResult);
    return beginResult;
  }

  ++gState.frameOrdinal;
  gState.frameOpen = true;
  gState.acquiredThisFrame = false;
  gState.acquireResult = XR_ENVIRONMENT_DEPTH_NOT_AVAILABLE_META;
  setupDepthLocked();
  const bool canAcquire = gState.depthStarted && gState.depthProvider != nullptr &&
                          gState.localSpace != nullptr && gState.predictedDisplayTime != 0;
  if (canAcquire) {
    const XrEnvironmentDepthImageAcquireInfoMETA acquireInfo{
        XR_TYPE_ENVIRONMENT_DEPTH_IMAGE_ACQUIRE_INFO_META,
        nullptr,
        gState.localSpace,
        gState.predictedDisplayTime};
    XrEnvironmentDepthImageMETA image{};
    image.type = XR_TYPE_ENVIRONMENT_DEPTH_IMAGE_META;
    image.views[0].type = XR_TYPE_ENVIRONMENT_DEPTH_IMAGE_VIEW_META;
    image.views[1].type = XR_TYPE_ENVIRONMENT_DEPTH_IMAGE_VIEW_META;
    gState.acquireResult = gState.downstream.acquireEnvironmentDepthImage(
        gState.depthProvider, &acquireInfo, &image);
    gState.acquiredThisFrame = gState.acquireResult == XR_SUCCESS;
    if (gState.acquiredThisFrame) {
      gState.acquiredSwapchainIndex = image.swapchainIndex;
    }
  }
  if (gState.frameOrdinal <= 3 || gState.frameOrdinal % 300 == 0) {
    marker("channel=frame status=begin-intercepted frameOrdinal=%llu sessionOrdinal=%llu exactSession=true downstreamResult=%d acquisitionAttempted=%s acquireResult=%d acquiredThisFrame=%s",
           static_cast<unsigned long long>(gState.frameOrdinal),
           static_cast<unsigned long long>(gState.sessionOrdinal),
           beginResult,
           canAcquire ? "true" : "false",
           gState.acquireResult,
           gState.acquiredThisFrame ? "true" : "false");
  }
  return beginResult;
}

extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrEndFrame(
    XrSession session, const XrFrameEndInfo* endInfo) {
  PFN_xrEndFrame downstreamEnd = nullptr;
  uint64_t frameOrdinal = 0;
  bool exactSession = false;
  bool matchingBegin = false;
  bool acquiredThisFrame = false;
  XrResult acquireResult = XR_ENVIRONMENT_DEPTH_NOT_AVAILABLE_META;
  bool gpuAttempted = false;
  bool gpuSubmitted = false;
  bool gpuDropped = false;
  bool gpuSampleable = false;
  bool fragmentSampleEvidence = false;
  VkResult gpuResult = VK_SUCCESS;
  uint64_t submittedGeneration = 0;
  uint64_t readyGeneration = 0;
  bool logGpuBoundary = false;
  {
    std::lock_guard lock(gMutex);
    downstreamEnd = gState.downstream.endFrame;
    exactSession = session == gState.session;
    matchingBegin = exactSession && gState.frameOpen;
    frameOrdinal = gState.frameOrdinal;
    acquiredThisFrame = matchingBegin && gState.acquiredThisFrame;
    acquireResult = gState.acquireResult;
    gpuAttempted = matchingBegin && gState.applicationGpuAttemptedThisFrame;
    gpuSubmitted = matchingBegin && gState.applicationGpuSubmittedThisFrame;
    gpuDropped = matchingBegin && gState.applicationGpuDroppedThisFrame;
    gpuSampleable = gState.applicationGpuSampleable;
    fragmentSampleEvidence = gState.applicationGpuFragmentSampleEvidence;
    gpuResult = gState.applicationGpuResult;
    submittedGeneration = gState.applicationGpuSubmittedGeneration;
    readyGeneration = gState.applicationGpuReadyGeneration;
    processSpatialSubmitRequestsLocked(frameOrdinal, matchingBegin);
    logGpuBoundary =
        (gpuSubmitted && (submittedGeneration <= 3 || submittedGeneration % 300 == 0)) ||
        (gpuSampleable && (readyGeneration <= 3 || readyGeneration % 300 == 0));
    if (matchingBegin &&
        (frameOrdinal <= 3 || frameOrdinal % 300 == 0 || logGpuBoundary || gpuDropped)) {
      marker("channel=frame status=before-end-forward frameOrdinal=%llu sessionOrdinal=%llu exactSession=true matchingBegin=true acquireResult=%d acquiredThisFrame=%s gpuAttempted=%s gpuWorkQueuedBeforeEnd=%s gpuDropped=%s vkResult=%d submittedGeneration=%llu readyGeneration=%llu deviceLocalSampleable=%s fragmentSampleEvidence=%s perFrameHostFenceWait=false sourceLifetimeRule=queued-before-end-runtime-waits-before-rewrite reusableDepth=%s",
             static_cast<unsigned long long>(frameOrdinal),
             static_cast<unsigned long long>(gState.sessionOrdinal),
             acquireResult,
             acquiredThisFrame ? "true" : "false",
             gpuAttempted ? "true" : "false",
             gpuSubmitted ? "true" : "false",
             gpuDropped ? "true" : "false",
             gpuResult,
             static_cast<unsigned long long>(submittedGeneration),
             static_cast<unsigned long long>(readyGeneration),
             gpuSampleable ? "true" : "false",
             fragmentSampleEvidence ? "true" : "false",
             gpuSampleable ? "true" : "false");
    }
  }
  if (downstreamEnd == nullptr) {
    return XR_ERROR_FUNCTION_UNSUPPORTED;
  }

  const XrResult endResult = downstreamEnd(session, endInfo);
  {
    std::lock_guard lock(gMutex);
    if (matchingBegin) {
      clearFrameStateLocked();
    }
  }
  if (frameOrdinal <= 3 || frameOrdinal % 300 == 0 || logGpuBoundary ||
      endResult != XR_SUCCESS || !matchingBegin || gpuDropped) {
    marker("channel=frame status=end-forwarded frameOrdinal=%llu exactSession=%s matchingBegin=%s downstreamResult=%d gpuWorkWasQueuedBeforeEnd=%s submittedGeneration=%llu readyGeneration=%llu deviceLocalSampleable=%s fragmentSampleEvidence=%s dataPlane=device-local-d16 perFrameHostFenceWait=false reusableDepth=%s",
           static_cast<unsigned long long>(frameOrdinal),
           exactSession ? "true" : "false",
           matchingBegin ? "true" : "false",
           endResult,
           gpuSubmitted ? "true" : "false",
           static_cast<unsigned long long>(submittedGeneration),
           static_cast<unsigned long long>(readyGeneration),
           gpuSampleable ? "true" : "false",
           fragmentSampleEvidence ? "true" : "false",
           gpuSampleable ? "true" : "false");
  }
  return endResult;
}

extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrCreateEnvironmentDepthProviderMETA(
    XrSession session,
    const XrEnvironmentDepthProviderCreateInfoMETA* createInfo,
    XrEnvironmentDepthProviderMETA* environmentDepthProvider) {
  PFN_xrCreateEnvironmentDepthProviderMETA downstreamCreate = nullptr;
  bool exactSession = false;
  {
    std::lock_guard lock(gMutex);
    downstreamCreate = gState.downstream.createEnvironmentDepthProvider;
    exactSession = session == gState.session;
  }
  if (downstreamCreate == nullptr) {
    marker("channel=application-depth status=provider-create-forwarded ownership=application-call exactSession=%s downstreamResult=%d providerRecorded=false",
           exactSession ? "true" : "false",
           XR_ERROR_FUNCTION_UNSUPPORTED);
    return XR_ERROR_FUNCTION_UNSUPPORTED;
  }

  const XrResult result = downstreamCreate(session, createInfo, environmentDepthProvider);
  bool providerRecorded = false;
  {
    std::lock_guard lock(gMutex);
    if (exactSession) {
      gState.applicationDepthCreateAttempted = true;
      if (result >= XR_SUCCESS && environmentDepthProvider != nullptr &&
          *environmentDepthProvider != nullptr) {
        gState.applicationDepthProvider = *environmentDepthProvider;
        providerRecorded = true;
      }
    }
  }
  marker("channel=application-depth status=provider-create-forwarded ownership=application-call exactSession=%s downstreamResult=%d providerRecorded=%s layerOwnedAcquireEnabled=false",
         exactSession ? "true" : "false",
         result,
         providerRecorded ? "true" : "false");
  return result;
}

extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrDestroyEnvironmentDepthProviderMETA(
    XrEnvironmentDepthProviderMETA environmentDepthProvider) {
  PFN_xrDestroyEnvironmentDepthProviderMETA downstreamDestroy = nullptr;
  bool exactProvider = false;
  {
    std::lock_guard lock(gMutex);
    downstreamDestroy = gState.downstream.destroyEnvironmentDepthProvider;
    exactProvider = environmentDepthProvider == gState.applicationDepthProvider;
  }
  if (downstreamDestroy == nullptr) {
    return XR_ERROR_FUNCTION_UNSUPPORTED;
  }
  const XrResult result = downstreamDestroy(environmentDepthProvider);
  if (result == XR_SUCCESS && exactProvider) {
    std::lock_guard lock(gMutex);
    gState.applicationDepthProvider = nullptr;
    gState.applicationDepthSwapchain = nullptr;
    gState.applicationDepthStarted = false;
  }
  marker("channel=application-depth status=provider-destroy-forwarded ownership=application-call exactProvider=%s downstreamResult=%d",
         exactProvider ? "true" : "false",
         result);
  return result;
}

extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrStartEnvironmentDepthProviderMETA(
    XrEnvironmentDepthProviderMETA environmentDepthProvider) {
  PFN_xrStartEnvironmentDepthProviderMETA downstreamStart = nullptr;
  bool exactProvider = false;
  {
    std::lock_guard lock(gMutex);
    downstreamStart = gState.downstream.startEnvironmentDepthProvider;
    exactProvider = environmentDepthProvider == gState.applicationDepthProvider;
  }
  if (downstreamStart == nullptr) {
    return XR_ERROR_FUNCTION_UNSUPPORTED;
  }
  const XrResult result = downstreamStart(environmentDepthProvider);
  if (result == XR_SUCCESS && exactProvider) {
    std::lock_guard lock(gMutex);
    gState.applicationDepthStarted = true;
  }
  marker("channel=application-depth status=provider-start-forwarded ownership=application-call exactProvider=%s downstreamResult=%d",
         exactProvider ? "true" : "false",
         result);
  return result;
}

extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrStopEnvironmentDepthProviderMETA(
    XrEnvironmentDepthProviderMETA environmentDepthProvider) {
  PFN_xrStopEnvironmentDepthProviderMETA downstreamStop = nullptr;
  bool exactProvider = false;
  {
    std::lock_guard lock(gMutex);
    downstreamStop = gState.downstream.stopEnvironmentDepthProvider;
    exactProvider = environmentDepthProvider == gState.applicationDepthProvider;
  }
  if (downstreamStop == nullptr) {
    return XR_ERROR_FUNCTION_UNSUPPORTED;
  }
  const XrResult result = downstreamStop(environmentDepthProvider);
  if (result == XR_SUCCESS && exactProvider) {
    std::lock_guard lock(gMutex);
    gState.applicationDepthStarted = false;
  }
  marker("channel=application-depth status=provider-stop-forwarded ownership=application-call exactProvider=%s downstreamResult=%d",
         exactProvider ? "true" : "false",
         result);
  return result;
}

extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrCreateEnvironmentDepthSwapchainMETA(
    XrEnvironmentDepthProviderMETA environmentDepthProvider,
    const XrEnvironmentDepthSwapchainCreateInfoMETA* createInfo,
    XrEnvironmentDepthSwapchainMETA* swapchain) {
  PFN_xrCreateEnvironmentDepthSwapchainMETA downstreamCreate = nullptr;
  bool exactProvider = false;
  {
    std::lock_guard lock(gMutex);
    downstreamCreate = gState.downstream.createEnvironmentDepthSwapchain;
    exactProvider = environmentDepthProvider == gState.applicationDepthProvider;
  }
  if (downstreamCreate == nullptr) {
    return XR_ERROR_FUNCTION_UNSUPPORTED;
  }
  const XrResult result = downstreamCreate(environmentDepthProvider, createInfo, swapchain);
  bool swapchainRecorded = false;
  if (result >= XR_SUCCESS && exactProvider && swapchain != nullptr && *swapchain != nullptr) {
    std::lock_guard lock(gMutex);
    gState.applicationDepthSwapchain = *swapchain;
    swapchainRecorded = true;
  }
  marker("channel=application-depth status=swapchain-create-forwarded ownership=application-call exactProvider=%s downstreamResult=%d swapchainRecorded=%s",
         exactProvider ? "true" : "false",
         result,
         swapchainRecorded ? "true" : "false");
  return result;
}

extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrDestroyEnvironmentDepthSwapchainMETA(
    XrEnvironmentDepthSwapchainMETA swapchain) {
  PFN_xrDestroyEnvironmentDepthSwapchainMETA downstreamDestroy = nullptr;
  bool exactSwapchain = false;
  {
    std::lock_guard lock(gMutex);
    downstreamDestroy = gState.downstream.destroyEnvironmentDepthSwapchain;
    exactSwapchain = swapchain == gState.applicationDepthSwapchain;
  }
  if (downstreamDestroy == nullptr) {
    return XR_ERROR_FUNCTION_UNSUPPORTED;
  }
  if (exactSwapchain) {
    std::lock_guard lock(gMutex);
    gDepthGpuHandoff.invalidateSourceSwapchain();
    gState.applicationDepthImages.clear();
    gState.applicationDepthWidth = 0;
    gState.applicationDepthHeight = 0;
  }
  const XrResult result = downstreamDestroy(swapchain);
  if (result == XR_SUCCESS && exactSwapchain) {
    std::lock_guard lock(gMutex);
    gState.applicationDepthSwapchain = nullptr;
  }
  marker("channel=application-depth status=swapchain-destroy-forwarded ownership=application-call exactSwapchain=%s downstreamResult=%d",
         exactSwapchain ? "true" : "false",
         result);
  return result;
}

extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrEnumerateEnvironmentDepthSwapchainImagesMETA(
    XrEnvironmentDepthSwapchainMETA swapchain,
    uint32_t imageCapacityInput,
    uint32_t* imageCountOutput,
    void* images) {
  PFN_xrEnumerateEnvironmentDepthSwapchainImagesMETA downstreamEnumerate = nullptr;
  bool exactSwapchain = false;
  {
    std::lock_guard lock(gMutex);
    downstreamEnumerate = gState.downstream.enumerateEnvironmentDepthSwapchainImages;
    exactSwapchain = swapchain == gState.applicationDepthSwapchain;
  }
  if (downstreamEnumerate == nullptr) {
    return XR_ERROR_FUNCTION_UNSUPPORTED;
  }
  const XrResult result =
      downstreamEnumerate(swapchain, imageCapacityInput, imageCountOutput, images);
  const uint32_t imageCount = imageCountOutput != nullptr && result >= XR_SUCCESS
                                  ? *imageCountOutput
                                  : 0U;
  bool vulkanImagesRecorded = false;
  bool copyConfigured = false;
  if (result >= XR_SUCCESS && exactSwapchain && imageCapacityInput > 0 && images != nullptr &&
      imageCountOutput != nullptr) {
    const uint32_t populatedCount = std::min(imageCapacityInput, *imageCountOutput);
    const auto* vulkanImages = static_cast<const XrSwapchainImageVulkanKHR*>(images);
    std::vector<VkImage> recordedImages;
    recordedImages.reserve(populatedCount);
    bool allVulkan = true;
    for (uint32_t index = 0; index < populatedCount; ++index) {
      if (vulkanImages[index].type != XR_TYPE_SWAPCHAIN_IMAGE_VULKAN_KHR ||
          vulkanImages[index].image == VK_NULL_HANDLE) {
        allVulkan = false;
        break;
      }
      recordedImages.push_back(vulkanImages[index].image);
    }
    if (allVulkan && !recordedImages.empty()) {
      std::lock_guard lock(gMutex);
      gState.applicationDepthImages = std::move(recordedImages);
      vulkanImagesRecorded = true;
      configureApplicationDepthCopyLocked("swapchain-images");
      copyConfigured = gDepthGpuHandoff.isConfigured();
    }
  }
  marker("channel=application-depth status=swapchain-images-forwarded ownership=application-call exactSwapchain=%s downstreamResult=%d capacityInput=%u imageCount=%u vulkanImagesRecorded=%s copyConfigured=%s",
         exactSwapchain ? "true" : "false",
         result,
         imageCapacityInput,
         imageCount,
         vulkanImagesRecorded ? "true" : "false",
         copyConfigured ? "true" : "false");
  return result;
}

extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrGetEnvironmentDepthSwapchainStateMETA(
    XrEnvironmentDepthSwapchainMETA swapchain,
    XrEnvironmentDepthSwapchainStateMETA* state) {
  PFN_xrGetEnvironmentDepthSwapchainStateMETA downstreamGetState = nullptr;
  bool exactSwapchain = false;
  {
    std::lock_guard lock(gMutex);
    downstreamGetState = gState.downstream.getEnvironmentDepthSwapchainState;
    exactSwapchain = swapchain == gState.applicationDepthSwapchain;
  }
  if (downstreamGetState == nullptr) {
    return XR_ERROR_FUNCTION_UNSUPPORTED;
  }
  const XrResult result = downstreamGetState(swapchain, state);
  const uint32_t width = state != nullptr && result >= XR_SUCCESS ? state->width : 0U;
  const uint32_t height = state != nullptr && result >= XR_SUCCESS ? state->height : 0U;
  bool copyConfigured = false;
  if (exactSwapchain && result >= XR_SUCCESS && width > 0 && height > 0) {
    std::lock_guard lock(gMutex);
    gState.applicationDepthWidth = width;
    gState.applicationDepthHeight = height;
    configureApplicationDepthCopyLocked("swapchain-state");
    copyConfigured = gDepthGpuHandoff.isConfigured();
  }
  marker("channel=application-depth status=swapchain-state-forwarded ownership=application-call exactSwapchain=%s downstreamResult=%d width=%u height=%u copyConfigured=%s",
         exactSwapchain ? "true" : "false",
         result,
         width,
         height,
         copyConfigured ? "true" : "false");
  return result;
}

extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrAcquireEnvironmentDepthImageMETA(
    XrEnvironmentDepthProviderMETA environmentDepthProvider,
    const XrEnvironmentDepthImageAcquireInfoMETA* acquireInfo,
    XrEnvironmentDepthImageMETA* environmentDepthImage) {
  PFN_xrAcquireEnvironmentDepthImageMETA downstreamAcquire = nullptr;
  PFN_xrLocateViews downstreamLocateViews = nullptr;
  bool exactProvider = false;
  bool frameOpenAtAcquire = false;
  XrSession trackedSession = nullptr;
  XrViewConfigurationType activeViewConfigurationType = 0;
  XrTime interceptedDisplayTime = 0;
  XrSpace interceptedSpace = nullptr;
  XrViewConfigurationType interceptedViewConfigurationType = 0;
  uint64_t interceptedViewSessionOrdinal = 0;
  uint64_t trackedSessionOrdinal = 0;
  uint64_t acquireSpaceOrdinal = 0;
  bool acquireSpaceAccepted = false;
  {
    std::lock_guard lock(gMutex);
    downstreamAcquire = gState.downstream.acquireEnvironmentDepthImage;
    downstreamLocateViews = gState.downstream.locateViews;
    exactProvider = environmentDepthProvider == gState.applicationDepthProvider;
    frameOpenAtAcquire = exactProvider && gState.frameOpen;
    trackedSession = gState.session;
    trackedSessionOrdinal = gState.sessionOrdinal;
    activeViewConfigurationType = gState.renderViewConfigurationType;
    interceptedDisplayTime = gState.renderViewDisplayTime;
    interceptedSpace = gState.renderViewSpace;
    interceptedViewConfigurationType = gState.renderViewConfigurationType;
    interceptedViewSessionOrdinal = gState.renderViewSessionOrdinal;
    if (acquireInfo != nullptr) {
      const auto spaceIterator = gState.liveSpaceOrdinals.find(acquireInfo->space);
      if (spaceIterator != gState.liveSpaceOrdinals.end() &&
          !gState.destroyedSpaces.contains(acquireInfo->space)) {
        acquireSpaceOrdinal = spaceIterator->second;
        acquireSpaceAccepted = true;
      }
    }
  }
  if (downstreamAcquire == nullptr) {
    return XR_ERROR_FUNCTION_UNSUPPORTED;
  }
  const auto downstreamAcquireStart = std::chrono::steady_clock::now();
  const XrResult result =
      downstreamAcquire(environmentDepthProvider, acquireInfo, environmentDepthImage);
  const uint64_t downstreamAcquireNanoseconds = static_cast<uint64_t>(
      std::chrono::duration_cast<std::chrono::nanoseconds>(
          std::chrono::steady_clock::now() - downstreamAcquireStart)
          .count());
  DirectRenderViewOutcome directViews{};
  directViews.displayTime = acquireInfo != nullptr ? acquireInfo->displayTime : 0;
  directViews.space = acquireInfo != nullptr ? acquireInfo->space : nullptr;
  directViews.spaceOrdinal = acquireSpaceOrdinal;
  directViews.sessionOrdinal = trackedSessionOrdinal;
  directViews.viewConfigurationType = activeViewConfigurationType;
  directViews.spaceAccepted = acquireSpaceAccepted;
  directViews.interceptedExactMatch =
      interceptedDisplayTime == directViews.displayTime &&
      interceptedSpace == directViews.space &&
      interceptedViewConfigurationType == activeViewConfigurationType &&
      interceptedViewSessionOrdinal == trackedSessionOrdinal;
  const bool directLocateInputsValid =
      result == XR_SUCCESS && exactProvider && frameOpenAtAcquire &&
      downstreamLocateViews != nullptr && trackedSession != nullptr &&
      acquireInfo != nullptr && acquireInfo->displayTime > 0 && acquireSpaceAccepted &&
      activeViewConfigurationType == kPrimaryStereoViewConfiguration;
  if (directLocateInputsValid) {
    directViews.attempted = true;
    XrViewLocateInfo locateInfo{
        XR_TYPE_VIEW_LOCATE_INFO,
        nullptr,
        activeViewConfigurationType,
        acquireInfo->displayTime,
        acquireInfo->space};
    XrViewState viewState{XR_TYPE_VIEW_STATE, nullptr, 0};
    directViews.views[0].type = XR_TYPE_VIEW;
    directViews.views[1].type = XR_TYPE_VIEW;
    directViews.result = downstreamLocateViews(
        trackedSession,
        &locateInfo,
        &viewState,
        static_cast<uint32_t>(directViews.views.size()),
        &directViews.viewCount,
        directViews.views.data());
    directViews.stateFlags = viewState.viewStateFlags;
    directViews.valid = directViews.result == XR_SUCCESS && directViews.viewCount == 2U &&
                        (directViews.stateFlags & kRequiredViewValidityBits) ==
                            kRequiredViewValidityBits;
  }
  bool matchingFrame = false;
  bool providerStarted = false;
  uint64_t frameOrdinal = 0;
  bool shouldLog = result != XR_SUCCESS && result != XR_ENVIRONMENT_DEPTH_NOT_AVAILABLE_META;
  uint64_t acquireOrdinal = 0;
  uint64_t directViewSuccessCount = 0;
  uint64_t directViewFailureCount = 0;
  bool shouldLogDirectViews = false;
  bool directViewGenerationChanged = false;
  bool directViewValidityTransition = false;
  uint32_t swapchainIndex = 0;
  DepthGpuEnqueueOutcome gpuOutcome{};
  DepthGpuCompletionOutcome completionOutcome{};
  {
    std::lock_guard lock(gMutex);
    matchingFrame = exactProvider && gState.frameOpen;
    frameOrdinal = gState.frameOrdinal;
    providerStarted = exactProvider && gState.applicationDepthStarted;
    if (exactProvider) {
      acquireOrdinal = ++gState.applicationAcquireOrdinal;
      shouldLog = shouldLog || acquireOrdinal <= 3 || acquireOrdinal % 300 == 0;
    }
    const bool movingHeadObservedBefore = gState.movingHeadObserved;
    if (directViews.valid) {
      ++gState.directRenderViewLocateOrdinal;
      ++gState.directRenderViewSuccessCount;
      const float motionDelta = gState.directRenderViewPreviousValid
                                    ? renderViewMotionDelta(
                                          gState.previousDirectRenderViews, directViews.views)
                                    : 0.0F;
      if (gState.directRenderViewPreviousValid && motionDelta > 0.000001F) {
        gState.movingHeadObserved = true;
      }
      directViews.movingHeadObserved = gState.movingHeadObserved;
      gState.previousDirectRenderViews = directViews.views;
      gState.directRenderViewPreviousValid = true;
    } else if (result == XR_SUCCESS && exactProvider && frameOpenAtAcquire) {
      ++gState.directRenderViewFailureCount;
    }
    directViewSuccessCount = gState.directRenderViewSuccessCount;
    directViewFailureCount = gState.directRenderViewFailureCount;
    if (result == XR_SUCCESS && exactProvider && frameOpenAtAcquire) {
      directViewGenerationChanged =
          !gState.directRenderViewMarkerStateObserved ||
          gState.lastDirectRenderViewMarkerSessionOrdinal != directViews.sessionOrdinal ||
          gState.lastDirectRenderViewMarkerSpaceOrdinal != directViews.spaceOrdinal;
      directViewValidityTransition =
          !gState.directRenderViewMarkerStateObserved ||
          gState.lastDirectRenderViewMarkerValid != directViews.valid;
      const bool movingHeadTransition =
          !movingHeadObservedBefore && gState.movingHeadObserved;
      shouldLogDirectViews = !directViews.valid || directViewGenerationChanged ||
                             directViewValidityTransition || movingHeadTransition ||
                             (directViews.valid &&
                              gState.directRenderViewSuccessCount % 300U == 0U);
      if (shouldLogDirectViews) {
        gState.directRenderViewMarkerStateObserved = true;
        gState.lastDirectRenderViewMarkerSessionOrdinal = directViews.sessionOrdinal;
        gState.lastDirectRenderViewMarkerSpaceOrdinal = directViews.spaceOrdinal;
        gState.lastDirectRenderViewMarkerValid = directViews.valid;
      }
    }
    if (result == XR_SUCCESS && environmentDepthImage != nullptr) {
      swapchainIndex = environmentDepthImage->swapchainIndex;
      if (matchingFrame) {
        DepthGpuFrameMetadata metadata{};
        metadata.frameOrdinal = frameOrdinal;
        metadata.displayTime = acquireInfo != nullptr ? acquireInfo->displayTime : 0;
        metadata.nearZ = environmentDepthImage->nearZ;
        metadata.farZ = environmentDepthImage->farZ;
        metadata.validMask = 0x1U;
        if (std::isfinite(metadata.nearZ) && std::isfinite(metadata.farZ) &&
            metadata.farZ > metadata.nearZ) {
          metadata.validMask |= 0x2U;
        }
        if (metadata.displayTime > 0) {
          metadata.validMask |= 0x4U;
        }
        auto* outputChain = static_cast<XrBaseOutStructure*>(environmentDepthImage->next);
        while (outputChain != nullptr) {
          if (outputChain->type == XR_TYPE_ENVIRONMENT_DEPTH_IMAGE_TIMESTAMP_META) {
            const auto* timestamp =
                reinterpret_cast<const XrEnvironmentDepthImageTimestampMETA*>(outputChain);
            metadata.captureTime = timestamp->captureTime;
            if (metadata.captureTime > 0) {
              metadata.validMask |= 0x8U;
            }
            break;
          }
          outputChain = outputChain->next;
        }
        for (uint32_t eye = 0; eye < 2; ++eye) {
          const XrEnvironmentDepthImageViewMETA& sourceView = environmentDepthImage->views[eye];
          DepthGpuViewMetadata& destinationView = metadata.depthViews[eye];
          destinationView.fov[0] = sourceView.fov.angleLeft;
          destinationView.fov[1] = sourceView.fov.angleRight;
          destinationView.fov[2] = sourceView.fov.angleUp;
          destinationView.fov[3] = sourceView.fov.angleDown;
          destinationView.orientation[0] = sourceView.pose.orientation.x;
          destinationView.orientation[1] = sourceView.pose.orientation.y;
          destinationView.orientation[2] = sourceView.pose.orientation.z;
          destinationView.orientation[3] = sourceView.pose.orientation.w;
          destinationView.position[0] = sourceView.pose.position.x;
          destinationView.position[1] = sourceView.pose.position.y;
          destinationView.position[2] = sourceView.pose.position.z;
          metadata.validMask |= (0x10U << eye);
        }
        if (directViews.valid) {
          metadata.renderViewDisplayTime = directViews.displayTime;
          metadata.renderViewStateFlags = directViews.stateFlags;
          metadata.renderViewSpaceToken = static_cast<uint64_t>(
              reinterpret_cast<uintptr_t>(directViews.space));
          metadata.renderViewSpaceGeneration = directViews.spaceOrdinal;
          metadata.renderViewSessionGeneration = directViews.sessionOrdinal;
          metadata.renderViewConfigurationType =
              static_cast<uint32_t>(directViews.viewConfigurationType);
          metadata.renderViewLocateResult = directViews.result;
          metadata.renderViewSource = 1U;
          for (uint32_t eye = 0; eye < 2; ++eye) {
            const XrView& sourceView = directViews.views[eye];
            DepthGpuViewMetadata& destinationView = metadata.renderViews[eye];
            destinationView.fov[0] = sourceView.fov.angleLeft;
            destinationView.fov[1] = sourceView.fov.angleRight;
            destinationView.fov[2] = sourceView.fov.angleUp;
            destinationView.fov[3] = sourceView.fov.angleDown;
            destinationView.orientation[0] = sourceView.pose.orientation.x;
            destinationView.orientation[1] = sourceView.pose.orientation.y;
            destinationView.orientation[2] = sourceView.pose.orientation.z;
            destinationView.orientation[3] = sourceView.pose.orientation.w;
            destinationView.position[0] = sourceView.pose.position.x;
            destinationView.position[1] = sourceView.pose.position.y;
            destinationView.position[2] = sourceView.pose.position.z;
            metadata.validMask |= (0x40U << eye);
          }
          metadata.validMask |= 0x100U;
        }
        gpuOutcome = gDepthGpuHandoff.enqueue(swapchainIndex, metadata);
        completionOutcome = gpuOutcome.completion;
        gState.applicationGpuAttemptedThisFrame = gpuOutcome.submit.attempted;
        gState.applicationGpuSubmittedThisFrame = gpuOutcome.submit.submitted;
        gState.applicationGpuDroppedThisFrame = gpuOutcome.submit.dropped;
        gState.applicationGpuResult = gpuOutcome.submit.result;
        gState.applicationGpuEnqueueCpuNanoseconds =
            gpuOutcome.submit.enqueueCpuNanoseconds;
        gState.applicationGpuSubmitCpuNanoseconds =
            gpuOutcome.submit.queueSubmitCpuNanoseconds;
        if (gpuOutcome.submit.submitted) {
          gState.applicationGpuSubmittedGeneration = gpuOutcome.submit.generation;
          shouldLog = shouldLog || gpuOutcome.submit.generation <= 3 ||
                      gpuOutcome.submit.generation % 300 == 0;
        }
        if (gpuOutcome.submit.dropped ||
            (gpuOutcome.submit.attempted && !gpuOutcome.submit.submitted)) {
          shouldLog = true;
        }
      }
    } else if (matchingFrame && gDepthGpuHandoff.isConfigured()) {
      completionOutcome = gDepthGpuHandoff.poll(frameOrdinal);
    }
    if (completionOutcome.sampleable) {
      gState.applicationGpuSampleable = true;
      gState.applicationGpuFragmentSampleEvidence =
          completionOutcome.fragmentSampleEvidence;
      gState.applicationGpuPollCpuNanoseconds = completionOutcome.pollCpuNanoseconds;
      if (completionOutcome.completionObserved) {
        gState.applicationGpuReadyGeneration = completionOutcome.generation;
        gState.applicationGpuReadyFrameOrdinal = completionOutcome.frameOrdinal;
        gState.applicationGpuReadyRingIndex = completionOutcome.ringIndex;
        gState.applicationGpuCopyNanoseconds =
            completionOutcome.gpuDepthCopyNanoseconds;
        gState.applicationGpuConsumerNanoseconds =
            completionOutcome.gpuFragmentConsumerNanoseconds;
        gState.applicationGpuTotalNanoseconds = completionOutcome.gpuTotalNanoseconds;
        gState.applicationGpuStaleFrameCount = completionOutcome.staleFrameCount;
        gState.applicationGpuDiagnosticMinimum = completionOutcome.diagnosticMinimum;
        gState.applicationGpuDiagnosticMaximum = completionOutcome.diagnosticMaximum;
        gState.retainedDepthMetadata = {};
        gState.retainedDepthMetadata.type = XR_TYPE_ENVIRONMENT_DEPTH_IMAGE_META;
        gState.retainedDepthMetadata.nearZ = completionOutcome.metadata.nearZ;
        gState.retainedDepthMetadata.farZ = completionOutcome.metadata.farZ;
        for (uint32_t eye = 0; eye < 2; ++eye) {
          const DepthGpuViewMetadata& sourceView = completionOutcome.metadata.depthViews[eye];
          XrEnvironmentDepthImageViewMETA& destinationView =
              gState.retainedDepthMetadata.views[eye];
          destinationView.type = XR_TYPE_ENVIRONMENT_DEPTH_IMAGE_VIEW_META;
          destinationView.fov = {
              sourceView.fov[0], sourceView.fov[1], sourceView.fov[2], sourceView.fov[3]};
          destinationView.pose.orientation = {
              sourceView.orientation[0],
              sourceView.orientation[1],
              sourceView.orientation[2],
              sourceView.orientation[3]};
          destinationView.pose.position = {
              sourceView.position[0], sourceView.position[1], sourceView.position[2]};
        }
        shouldLog = shouldLog || completionOutcome.generation <= 3 ||
                    completionOutcome.generation % 300 == 0;
      }
    }
  }
  if (result == XR_SUCCESS && exactProvider && frameOpenAtAcquire &&
      shouldLogDirectViews) {
    marker(
        "channel=render-views status=depth-time-direct-locate acquireOrdinal=%llu frameOrdinal=%llu sessionOrdinal=%llu spaceOrdinal=%llu displayTime=%lld viewConfigurationType=%d spaceAccepted=%s attempted=%s downstreamResult=%d viewCount=%u viewStateFlags=%llu valid=%s interceptedExactMatch=%s movingHeadObserved=%s successCount=%llu failureCount=%llu generationChanged=%s validityTransition=%s markerPolicy=transition-failure-periodic-300 recursionAvoided=true locateFunction=downstream-direct beforeMatchingEnd=true",
        static_cast<unsigned long long>(acquireOrdinal),
        static_cast<unsigned long long>(frameOrdinal),
        static_cast<unsigned long long>(directViews.sessionOrdinal),
        static_cast<unsigned long long>(directViews.spaceOrdinal),
        static_cast<long long>(directViews.displayTime),
        directViews.viewConfigurationType,
        directViews.spaceAccepted ? "true" : "false",
        directViews.attempted ? "true" : "false",
        directViews.result,
        directViews.viewCount,
        static_cast<unsigned long long>(directViews.stateFlags),
        directViews.valid ? "true" : "false",
        directViews.interceptedExactMatch ? "true" : "false",
        directViews.movingHeadObserved ? "true" : "false",
        static_cast<unsigned long long>(directViewSuccessCount),
        static_cast<unsigned long long>(directViewFailureCount),
        directViewGenerationChanged ? "true" : "false",
        directViewValidityTransition ? "true" : "false");
  }
  if (shouldLog) {
    marker("channel=application-depth status=acquire-forwarded ownership=application-call acquireOrdinal=%llu frameOrdinal=%llu exactProvider=%s providerStarted=%s matchingFrame=%s downstreamResult=%d downstreamAcquireNs=%llu swapchainIndex=%u gpuAttempted=%s gpuSubmitted=%s gpuDropped=%s gpuStage=%s vkResult=%d submittedGeneration=%llu submittedRingIndex=%u enqueueCpuNs=%llu queueSubmitCpuNs=%llu completionObserved=%s readyGeneration=%llu readyFrameOrdinal=%llu readyRingIndex=%u staleFrames=%u pollCpuNs=%llu gpuDepthCopyNs=%llu gpuFragmentConsumerNs=%llu gpuTotalNs=%llu fragmentSampleEvidence=%s diagnosticMinimum=%.6f diagnosticMaximum=%.6f deviceLocalSampleable=%s perFrameHostFenceWait=false cpuDepthReadback=false dataPlane=device-local-d16 layerOwnedAcquireEnabled=false",
           static_cast<unsigned long long>(acquireOrdinal),
           static_cast<unsigned long long>(frameOrdinal),
           exactProvider ? "true" : "false",
           providerStarted ? "true" : "false",
           matchingFrame ? "true" : "false",
           result,
           static_cast<unsigned long long>(downstreamAcquireNanoseconds),
           swapchainIndex,
           gpuOutcome.submit.attempted ? "true" : "false",
           gpuOutcome.submit.submitted ? "true" : "false",
           gpuOutcome.submit.dropped ? "true" : "false",
           gpuOutcome.submit.stage,
           gpuOutcome.submit.result,
           static_cast<unsigned long long>(gpuOutcome.submit.generation),
           gpuOutcome.submit.ringIndex,
           static_cast<unsigned long long>(gpuOutcome.submit.enqueueCpuNanoseconds),
           static_cast<unsigned long long>(gpuOutcome.submit.queueSubmitCpuNanoseconds),
           completionOutcome.completionObserved ? "true" : "false",
           static_cast<unsigned long long>(completionOutcome.generation),
           static_cast<unsigned long long>(completionOutcome.frameOrdinal),
           completionOutcome.ringIndex,
           completionOutcome.staleFrameCount,
           static_cast<unsigned long long>(completionOutcome.pollCpuNanoseconds),
           static_cast<unsigned long long>(completionOutcome.gpuDepthCopyNanoseconds),
           static_cast<unsigned long long>(completionOutcome.gpuFragmentConsumerNanoseconds),
           static_cast<unsigned long long>(completionOutcome.gpuTotalNanoseconds),
           completionOutcome.fragmentSampleEvidence ? "true" : "false",
           completionOutcome.diagnosticMinimum,
           completionOutcome.diagnosticMaximum,
           completionOutcome.sampleable ? "true" : "false");
  }
  return result;
}

extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrDestroyInstance(XrInstance instance) {
  PFN_xrDestroyInstance downstreamDestroy = nullptr;
  uint64_t instanceOrdinal = 0;
  {
    std::lock_guard lock(gMutex);
    downstreamDestroy = gState.downstream.destroyInstance;
    if (instance == gState.instance) {
      instanceOrdinal = gState.instanceOrdinal;
      gDepthConsumerBridge.resetSession();
      gDepthGpuHandoff.resetSession();
      resetSpatialSubmitBrokerLocked();
      cleanupDepthLocked();
      gState.instance = nullptr;
      gState.vulkanInstance = VK_NULL_HANDLE;
      gState.session = nullptr;
      gState.sdkVulkanBinding = {};
      gState.sdkQueue = VK_NULL_HANDLE;
      gState.consumerContextToken = 0;
    }
  }
  if (downstreamDestroy == nullptr) {
    return XR_ERROR_FUNCTION_UNSUPPORTED;
  }
  const XrResult result = downstreamDestroy(instance);
  marker("channel=lifecycle status=instance-destroyed instanceOrdinal=%llu downstreamResult=%d",
         static_cast<unsigned long long>(instanceOrdinal),
         result);
  return result;
}

namespace {

#if 0  // Isolated qualification ABI is intentionally absent from the production layer binary.
int32_t abiSnapshot(rq_depth_gpu_snapshot_v1* outSnapshot) {
  std::lock_guard lock(gMutex);
  return gDepthConsumerBridge.snapshot(outSnapshot);
}

int32_t abiQualifyAndroidSurface(void* nativeWindow, uint32_t width, uint32_t height) {
  std::lock_guard lock(gMutex);
  const bool enabledRequestProven = gState.vulkanInstanceCreateObserved &&
                                    gState.vulkanInstanceSurfaceRequested &&
                                    gState.vulkanInstanceAndroidSurfaceRequested &&
                                    gState.vulkanDeviceCreateObserved &&
                                    gState.vulkanDeviceSwapchainRequested;
  const int32_t status = enabledRequestProven
                             ? gDepthConsumerBridge.qualifyAndroidSurface(
                                   static_cast<ANativeWindow*>(nativeWindow), width, height)
                             : RQ_DEPTH_GPU_STATUS_UNSUPPORTED;
  rq_depth_gpu_snapshot_v1 snapshot{};
  snapshot.struct_size = sizeof(snapshot);
  snapshot.abi_version = RQ_DEPTH_GPU_ABI_V1;
  gDepthConsumerBridge.snapshot(&snapshot);
  marker(
      "channel=consumer-abi status=wsi-qualification result=%d vkResult=%d enabledRequestInstanceObserved=%s khrSurfaceRequested=%s khrAndroidSurfaceRequested=%s enabledRequestDeviceObserved=%s khrSwapchainRequested=%s actualSurfaceCreate=%s actualPresentSupport=%s actualSwapchainCreate=%s actualAcquireSubmitPresentCandidate=%s surfaceFormat=%u presentMode=%u swapchainImageCount=%u queueSubmissionOwner=layer-frame-thread",
      status,
      snapshot.last_vk_result,
      gState.vulkanInstanceCreateObserved ? "true" : "false",
      gState.vulkanInstanceSurfaceRequested ? "true" : "false",
      gState.vulkanInstanceAndroidSurfaceRequested ? "true" : "false",
      gState.vulkanDeviceCreateObserved ? "true" : "false",
      gState.vulkanDeviceSwapchainRequested ? "true" : "false",
      status == RQ_DEPTH_GPU_STATUS_OK ? "true" : "false",
      status == RQ_DEPTH_GPU_STATUS_OK ? "true" : "false",
      status == RQ_DEPTH_GPU_STATUS_OK ? "true" : "false",
      status == RQ_DEPTH_GPU_STATUS_OK ? "true" : "false",
      snapshot.surface_format,
      snapshot.present_mode,
      snapshot.swapchain_image_count);
  return status;
}

int32_t abiQualifyAhardwareBufferYcbcr() {
  std::lock_guard lock(gMutex);
  const bool enabledRequestProven = gState.vulkanDeviceCreateObserved &&
                                    gState.vulkanDeviceAhbRequested &&
                                    gState.vulkanDeviceYcbcrExtensionRequested &&
                                    gState.vulkanDeviceYcbcrFeatureRequested;
  const int32_t status = enabledRequestProven
                             ? gDepthConsumerBridge.qualifyAhardwareBufferYcbcr()
                             : RQ_DEPTH_GPU_STATUS_UNSUPPORTED;
  rq_depth_gpu_snapshot_v1 snapshot{};
  snapshot.struct_size = sizeof(snapshot);
  snapshot.abi_version = RQ_DEPTH_GPU_ABI_V1;
  gDepthConsumerBridge.snapshot(&snapshot);
  marker(
      "channel=consumer-abi status=ahb-ycbcr-qualification result=%d vkResult=%d enabledRequestDeviceObserved=%s androidAhbRequested=%s khrSamplerYcbcrRequested=%s samplerYcbcrFeatureRequested=%s actualAhbAllocated=%s actualAhbPropertiesQueried=%s actualExternalImageImported=%s actualSamplerYcbcrCreated=%s actualImageViewSamplerCreated=%s producer=synthetic-y8cb8cr8-420 cameraOrCodecProducerValidated=false",
      status,
      snapshot.last_vk_result,
      gState.vulkanDeviceCreateObserved ? "true" : "false",
      gState.vulkanDeviceAhbRequested ? "true" : "false",
      gState.vulkanDeviceYcbcrExtensionRequested ? "true" : "false",
      gState.vulkanDeviceYcbcrFeatureRequested ? "true" : "false",
      status == RQ_DEPTH_GPU_STATUS_OK ? "true" : "false",
      status == RQ_DEPTH_GPU_STATUS_OK ? "true" : "false",
      status == RQ_DEPTH_GPU_STATUS_OK ? "true" : "false",
      status == RQ_DEPTH_GPU_STATUS_OK ? "true" : "false",
      status == RQ_DEPTH_GPU_STATUS_OK ? "true" : "false");
  return status;
}

int32_t abiAcquireLatest(
    uint64_t expectedDeviceToken,
    uint64_t expectedSessionGeneration,
    rq_depth_gpu_frame_v1* outFrame) {
  std::lock_guard lock(gMutex);
  const int32_t status = gDepthConsumerBridge.acquireLatest(
      expectedDeviceToken, expectedSessionGeneration, outFrame);
  if (status == RQ_DEPTH_GPU_STATUS_DEVICE_MISMATCH ||
      status == RQ_DEPTH_GPU_STATUS_SESSION_MISMATCH || status == RQ_DEPTH_GPU_STATUS_OK) {
    marker(
        "channel=consumer-abi status=acquire result=%d expectedDeviceMatch=%s expectedSessionMatch=%s leasePinned=%s generation=%llu leaseId=%llu renderViewsValid=%s",
        status,
        status != RQ_DEPTH_GPU_STATUS_DEVICE_MISMATCH ? "true" : "false",
        status != RQ_DEPTH_GPU_STATUS_SESSION_MISMATCH ? "true" : "false",
        status == RQ_DEPTH_GPU_STATUS_OK ? "true" : "false",
        static_cast<unsigned long long>(
            status == RQ_DEPTH_GPU_STATUS_OK ? outFrame->generation : 0),
        static_cast<unsigned long long>(
            status == RQ_DEPTH_GPU_STATUS_OK ? outFrame->lease_id : 0),
        status == RQ_DEPTH_GPU_STATUS_OK && (outFrame->valid_mask & 0x1C0U) == 0x1C0U
            ? "true"
            : "false");
  }
  return status;
}

int32_t abiEnqueuePresent(
    uint64_t expectedDeviceToken,
    uint64_t expectedSessionGeneration,
    uint64_t leaseId,
    uint64_t requestId) {
  std::lock_guard lock(gMutex);
  return gDepthConsumerBridge.enqueuePresent(
      expectedDeviceToken,
      expectedSessionGeneration,
      leaseId,
      requestId);
}

int32_t abiEnqueueQueueStress(
    uint64_t expectedDeviceToken,
    uint64_t expectedSessionGeneration,
    uint32_t lane,
    uint64_t requestId) {
  std::lock_guard lock(gMutex);
  return gDepthConsumerBridge.enqueueQueueStress(
      expectedDeviceToken,
      expectedSessionGeneration,
      lane,
      requestId);
}

int32_t abiEnqueueAhardwareBufferYcbcrSample(
    void* ahardwareBuffer,
    int32_t acquireFenceFd,
    uint32_t producerKind,
    uint64_t requestId) {
  std::lock_guard lock(gMutex);
  const bool enabledRequestProven = gState.vulkanDeviceCreateObserved &&
                                    gState.vulkanDeviceAhbRequested &&
                                    gState.vulkanDeviceYcbcrExtensionRequested &&
                                    gState.vulkanDeviceYcbcrFeatureRequested &&
                                    gState.vulkanDeviceExternalSemaphoreFdRequested;
  const int32_t status = enabledRequestProven
                             ? gDepthConsumerBridge.enqueueAhardwareBufferYcbcrSample(
                                   static_cast<AHardwareBuffer*>(ahardwareBuffer),
                                   acquireFenceFd,
                                   producerKind,
                                   requestId)
                             : RQ_DEPTH_GPU_STATUS_UNSUPPORTED;
  if (!enabledRequestProven && acquireFenceFd >= 0) {
    close(acquireFenceFd);
  }
  marker(
      "channel=consumer-abi status=ahb-ycbcr-real-producer-enqueue result=%d producerKind=%u requestId=%llu exactSdkDevice=true featurePhysicallySupported=%s featureRequestedBefore=%s featureForwardedAfter=%s featureEffectiveEnabled=%s externalSemaphoreFdPhysicallySupported=%s externalSemaphoreFdRequestedBefore=%s externalSemaphoreFdForwardedAfter=%s externalSemaphoreFdEffectiveEnabled=%s acquireFencePresent=%s semaphoreType=binary handleType=VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT acquireImport=temporary fdOwnership=producer-until-enqueue-layer-until-successful-import-vulkan-on-success-layer-closes-on-failure releaseExport=one-shot-payload-transfer-reset-before-slot-reuse queueSubmissionOwner=layer-frame-thread cpuReadbackUpload=false perFrameHostFenceWait=false",
      status,
      producerKind,
      static_cast<unsigned long long>(requestId),
      gState.vulkanDeviceYcbcrFeaturePhysicallySupported ? "true" : "false",
      gState.vulkanDeviceYcbcrFeatureRequestedBefore ? "true" : "false",
      (gState.vulkanDeviceYcbcrFeatureRequestedBefore ||
       gState.vulkanDeviceYcbcrFeatureAugmented)
          ? "true"
          : "false",
      gState.vulkanDeviceYcbcrFeatureRequested ? "true" : "false",
      gState.vulkanDeviceExternalSemaphoreFdPhysicallySupported ? "true" : "false",
      gState.vulkanDeviceExternalSemaphoreFdRequestedBefore ? "true" : "false",
      (gState.vulkanDeviceExternalSemaphoreFdRequestedBefore ||
       gState.vulkanDeviceExternalSemaphoreFdAugmented)
          ? "true"
          : "false",
      gState.vulkanDeviceExternalSemaphoreFdRequested ? "true" : "false",
      acquireFenceFd >= 0 ? "true" : "false");
  return status;
}

int32_t abiPollRequest(
    uint64_t requestId, rq_depth_gpu_request_result_v1* outResult) {
  std::lock_guard lock(gMutex);
  return gDepthConsumerBridge.pollRequest(requestId, outResult);
}

int32_t abiPollLease(uint64_t leaseId, uint32_t* outLeaseState) {
  std::lock_guard lock(gMutex);
  return gDepthConsumerBridge.pollLease(leaseId, outLeaseState);
}

void abiDestroyConsumer() {
  std::lock_guard lock(gMutex);
  gDepthConsumerBridge.requestConsumerDestroy();
  marker("channel=consumer-abi status=consumer-destroy-requested executionOwner=layer-frame-thread teardownWait=allowed-not-per-frame");
}

const rq_depth_gpu_api_v1 kDepthGpuApiV1{
    sizeof(rq_depth_gpu_api_v1),
    RQ_DEPTH_GPU_ABI_V1,
    abiSnapshot,
    abiQualifyAndroidSurface,
    abiQualifyAhardwareBufferYcbcr,
    abiAcquireLatest,
    abiEnqueuePresent,
    abiEnqueueQueueStress,
    abiEnqueueAhardwareBufferYcbcrSample,
    abiPollRequest,
    abiPollLease,
    abiDestroyConsumer};
#endif

int32_t abiV2GetDeviceBinding(rq_spatial_depth_device_binding_v2* outBinding) {
  if (outBinding == nullptr) {
    return RQ_DEPTH_GPU_STATUS_INVALID_ARGUMENT;
  }
  if (outBinding->struct_size != sizeof(rq_spatial_depth_device_binding_v2) ||
      outBinding->abi_version != RQ_SPATIAL_DEPTH_HANDOFF_ABI_V2) {
    return RQ_DEPTH_GPU_STATUS_ABI_MISMATCH;
  }
  std::lock_guard lock(gMutex);
  if (gState.session == nullptr || gState.consumerContextToken == 0 ||
      gState.sdkVulkanBinding.device == VK_NULL_HANDLE ||
      gState.sdkQueue == VK_NULL_HANDLE) {
    return RQ_DEPTH_GPU_STATUS_NOT_READY;
  }
  if (gSpatialShutdownRequested) {
    resetSpatialSubmitBrokerLocked();
    marker("channel=consumer-abi status=consumer-context-reopened abiVersion=2 sessionOrdinal=%llu sessionReplaced=false surfaceGenerationMustAdvance=true",
           static_cast<unsigned long long>(gState.sessionOrdinal));
  }
  const uint32_t structSize = outBinding->struct_size;
  const uint32_t abiVersion = outBinding->abi_version;
  *outBinding = {};
  outBinding->struct_size = structSize;
  outBinding->abi_version = abiVersion;
  outBinding->context_token = gState.consumerContextToken;
  outBinding->device_token = gDepthConsumerBridge.deviceToken();
  outBinding->session_generation = gState.sessionOrdinal;
  outBinding->instance_handle = static_cast<uint64_t>(
      reinterpret_cast<uintptr_t>(gState.sdkVulkanBinding.instance));
  outBinding->physical_device_handle = static_cast<uint64_t>(
      reinterpret_cast<uintptr_t>(gState.sdkVulkanBinding.physicalDevice));
  outBinding->device_handle = static_cast<uint64_t>(
      reinterpret_cast<uintptr_t>(gState.sdkVulkanBinding.device));
  outBinding->queue_handle = static_cast<uint64_t>(
      reinterpret_cast<uintptr_t>(gState.sdkQueue));
  outBinding->queue_family_index = gState.sdkVulkanBinding.queueFamilyIndex;
  outBinding->queue_index = gState.sdkVulkanBinding.queueIndex;
  outBinding->enabled_capability_mask =
      (gState.vulkanInstanceAndroidSurfaceRequested ? 1U : 0U) |
      (gState.vulkanDeviceSwapchainRequested ? 1U << 1U : 0U) |
      (gState.vulkanDeviceAhbRequested ? 1U << 2U : 0U) |
      (gState.vulkanDeviceYcbcrFeatureRequested ? 1U << 3U : 0U) |
      (gState.vulkanDeviceExternalSemaphoreFdRequested ? 1U << 4U : 0U);
  return RQ_DEPTH_GPU_STATUS_OK;
}

int32_t abiV2AcquireLatest(
    uint64_t expectedDeviceToken,
    uint64_t expectedSessionGeneration,
    rq_depth_gpu_frame_v1* outFrame) {
  std::lock_guard lock(gMutex);
  return gDepthConsumerBridge.acquireLatest(
      expectedDeviceToken, expectedSessionGeneration, outFrame);
}

int32_t abiV2EnqueueSubmitPresent(
    const rq_spatial_depth_submit_present_v2* request) {
  if (request == nullptr || request->request_id == 0) {
    return RQ_DEPTH_GPU_STATUS_INVALID_ARGUMENT;
  }
  if (request->struct_size != sizeof(rq_spatial_depth_submit_present_v2) ||
      request->abi_version != RQ_SPATIAL_DEPTH_HANDOFF_ABI_V2) {
    return RQ_DEPTH_GPU_STATUS_ABI_MISMATCH;
  }
  std::lock_guard lock(gMutex);
  const int32_t validation = validateSpatialRequestLocked(
      request->expected_context_token,
      request->expected_device_token,
      request->expected_session_generation);
  if (validation != RQ_DEPTH_GPU_STATUS_OK) {
    return validation;
  }
  if (gSpatialSubmitRequests.contains(request->request_id)) {
    return RQ_DEPTH_GPU_STATUS_INVALID_ARGUMENT;
  }
  if (gSpatialSubmitQueue.size() >= kMaximumSpatialSubmitRequests) {
    return RQ_DEPTH_GPU_STATUS_QUEUE_FULL;
  }
  SpatialSubmitRequestV2 queued{};
  queued.request = *request;
  queued.result.struct_size = sizeof(rq_depth_gpu_request_result_v1);
  queued.result.abi_version = RQ_DEPTH_GPU_ABI_V1;
  queued.result.request_id = request->request_id;
  queued.result.lease_id = request->lease_id;
  queued.result.kind = RQ_DEPTH_GPU_REQUEST_PRESENT;
  queued.result.state = RQ_DEPTH_GPU_REQUEST_STATE_QUEUED;
  queued.result.status = RQ_DEPTH_GPU_STATUS_PENDING;
  queued.result.queued_monotonic_ns = monotonicNanosecondsV2();
  gSpatialSubmitRequests.emplace(request->request_id, queued);
  gSpatialSubmitQueue.push_back(request->request_id);
  return RQ_DEPTH_GPU_STATUS_PENDING;
}

int32_t abiV2PollRequest(
    uint64_t requestId, rq_depth_gpu_request_result_v1* outResult) {
  if (requestId == 0 || outResult == nullptr) {
    return RQ_DEPTH_GPU_STATUS_INVALID_ARGUMENT;
  }
  if (outResult->struct_size != sizeof(rq_depth_gpu_request_result_v1) ||
      outResult->abi_version != RQ_DEPTH_GPU_ABI_V1) {
    return RQ_DEPTH_GPU_STATUS_ABI_MISMATCH;
  }
  std::lock_guard lock(gMutex);
  auto iterator = gSpatialSubmitRequests.find(requestId);
  if (iterator == gSpatialSubmitRequests.end()) {
    return RQ_DEPTH_GPU_STATUS_NOT_READY;
  }
  *outResult = iterator->second.result;
  const bool terminal =
      outResult->state == RQ_DEPTH_GPU_REQUEST_STATE_COMPLETE ||
      outResult->state == RQ_DEPTH_GPU_REQUEST_STATE_FAILED;
  const int32_t status =
      outResult->state == RQ_DEPTH_GPU_REQUEST_STATE_COMPLETE
          ? RQ_DEPTH_GPU_STATUS_OK
          : outResult->state == RQ_DEPTH_GPU_REQUEST_STATE_FAILED
                ? outResult->status
                : RQ_DEPTH_GPU_STATUS_PENDING;
  if (terminal) {
    gSpatialSubmitRequests.erase(iterator);
  }
  return status;
}

int32_t abiV2ReleaseLease(uint64_t expectedSessionGeneration, uint64_t leaseId) {
  if (expectedSessionGeneration == 0 || leaseId == 0) {
    return RQ_DEPTH_GPU_STATUS_INVALID_ARGUMENT;
  }
  std::lock_guard lock(gMutex);
  if (expectedSessionGeneration != gState.sessionOrdinal) {
    return RQ_DEPTH_GPU_STATUS_SESSION_MISMATCH;
  }
  const int32_t status = gDepthConsumerBridge.releaseLease(leaseId);
  if (status == RQ_DEPTH_GPU_STATUS_OK) {
    configureApplicationDepthCopyLocked("consumer-lease-release");
  }
  return status;
}

void abiV2RequestShutdown(uint64_t expectedSessionGeneration) {
  std::lock_guard lock(gMutex);
  if (expectedSessionGeneration == gState.sessionOrdinal) {
    gSpatialShutdownRequested = true;
    while (!gSpatialSubmitQueue.empty()) {
      const uint64_t requestId = gSpatialSubmitQueue.front();
      gSpatialSubmitQueue.pop_front();
      auto iterator = gSpatialSubmitRequests.find(requestId);
      if (iterator != gSpatialSubmitRequests.end()) {
        failSpatialSubmitRequestLocked(iterator->second, RQ_DEPTH_GPU_STATUS_SHUTDOWN);
      }
    }
    marker("channel=consumer-abi status=shutdown-requested abiVersion=2 sessionOrdinal=%llu pendingRequests=failed appObjectsMustBeDestroyedBeforeSessionReplacement=true sdkOwnedObjectsDestroyed=false",
           static_cast<unsigned long long>(gState.sessionOrdinal));
  }
}

static_assert(alignof(rq_spatial_depth_device_binding_v2) == alignof(uint64_t));
static_assert(alignof(rq_spatial_depth_submit_present_v2) == alignof(uint64_t));

const rq_spatial_depth_api_v2 kSpatialDepthApiV2{
    sizeof(rq_spatial_depth_api_v2),
    RQ_SPATIAL_DEPTH_HANDOFF_ABI_V2,
    abiV2GetDeviceBinding,
    abiV2AcquireLatest,
    abiV2EnqueueSubmitPresent,
    abiV2PollRequest,
    abiV2ReleaseLease,
    abiV2RequestShutdown};

}  // namespace

extern "C" XRAPI_ATTR const rq_spatial_depth_api_v2*
rq_spatial_depth_get_api_v2() {
  return &kSpatialDepthApiV2;
}

extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrGetInstanceProcAddr(
    XrInstance instance, const char* name, PFN_xrVoidFunction* function) {
  if (name == nullptr || function == nullptr) {
    return XR_ERROR_VALIDATION_FAILURE;
  }
  *function = nullptr;
#define RETURN_INTERCEPT(functionName)                                                        \
  if (std::strcmp(name, #functionName) == 0) {                                               \
    *function = reinterpret_cast<PFN_xrVoidFunction>(functionName);                          \
    return XR_SUCCESS;                                                                        \
  }
  RETURN_INTERCEPT(xrInitializeLoaderKHR)
  RETURN_INTERCEPT(xrCreateVulkanInstanceKHR)
  RETURN_INTERCEPT(xrCreateVulkanDeviceKHR)
  RETURN_INTERCEPT(xrCreateSession)
  RETURN_INTERCEPT(xrDestroySession)
  RETURN_INTERCEPT(xrCreateReferenceSpace)
  RETURN_INTERCEPT(xrDestroySpace)
  RETURN_INTERCEPT(xrWaitFrame)
  RETURN_INTERCEPT(xrLocateViews)
  RETURN_INTERCEPT(xrBeginFrame)
  RETURN_INTERCEPT(xrEndFrame)
  RETURN_INTERCEPT(xrDestroyInstance)
#undef RETURN_INTERCEPT

#define RETURN_APPLICATION_DEPTH_INTERCEPT(functionName, functionBit)                       \
  if (std::strcmp(name, #functionName) == 0) {                                               \
    bool firstResolution = false;                                                            \
    {                                                                                         \
      std::lock_guard lock(gMutex);                                                          \
      firstResolution = recordApplicationDepthFunctionResolutionLocked(functionBit);         \
    }                                                                                         \
    if (firstResolution) {                                                                    \
      marker("channel=application-depth status=function-intercepted ownership=application-call function=%s layerOwnedAcquireEnabled=false", \
             #functionName);                                                                  \
    }                                                                                         \
    *function = reinterpret_cast<PFN_xrVoidFunction>(functionName);                          \
    return XR_SUCCESS;                                                                        \
  }
  RETURN_APPLICATION_DEPTH_INTERCEPT(xrCreateEnvironmentDepthProviderMETA, kCreateProviderBit)
  RETURN_APPLICATION_DEPTH_INTERCEPT(xrDestroyEnvironmentDepthProviderMETA, kDestroyProviderBit)
  RETURN_APPLICATION_DEPTH_INTERCEPT(xrStartEnvironmentDepthProviderMETA, kStartProviderBit)
  RETURN_APPLICATION_DEPTH_INTERCEPT(xrStopEnvironmentDepthProviderMETA, kStopProviderBit)
  RETURN_APPLICATION_DEPTH_INTERCEPT(xrCreateEnvironmentDepthSwapchainMETA, kCreateSwapchainBit)
  RETURN_APPLICATION_DEPTH_INTERCEPT(xrDestroyEnvironmentDepthSwapchainMETA, kDestroySwapchainBit)
  RETURN_APPLICATION_DEPTH_INTERCEPT(xrEnumerateEnvironmentDepthSwapchainImagesMETA, kEnumerateSwapchainImagesBit)
  RETURN_APPLICATION_DEPTH_INTERCEPT(xrGetEnvironmentDepthSwapchainStateMETA, kGetSwapchainStateBit)
  RETURN_APPLICATION_DEPTH_INTERCEPT(xrAcquireEnvironmentDepthImageMETA, kAcquireImageBit)
#undef RETURN_APPLICATION_DEPTH_INTERCEPT

  PFN_xrGetInstanceProcAddr downstreamGetInstanceProcAddr = nullptr;
  {
    std::lock_guard lock(gMutex);
    downstreamGetInstanceProcAddr = gState.downstream.getInstanceProcAddr;
  }
  if (downstreamGetInstanceProcAddr == nullptr || instance == nullptr) {
    return XR_ERROR_FUNCTION_UNSUPPORTED;
  }
  return downstreamGetInstanceProcAddr(instance, name, function);
}

extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrCreateApiLayerInstance(
    const XrInstanceCreateInfo* createInfo,
    const XrApiLayerCreateInfo* apiLayerInfo,
    XrInstance* instance) {
  if (createInfo == nullptr || apiLayerInfo == nullptr || instance == nullptr ||
      apiLayerInfo->nextInfo == nullptr ||
      apiLayerInfo->nextInfo->nextGetInstanceProcAddr == nullptr ||
      apiLayerInfo->nextInfo->nextCreateApiLayerInstance == nullptr) {
    return XR_ERROR_INITIALIZATION_FAILED;
  }

  try {
    const bool requestedBefore = requestedExtension(createInfo, kDepthExtension);
    std::vector<const char*> forwardedExtensions;
    forwardedExtensions.reserve(createInfo->enabledExtensionCount + (requestedBefore ? 0U : 1U));
    for (uint32_t index = 0; index < createInfo->enabledExtensionCount; ++index) {
      forwardedExtensions.push_back(createInfo->enabledExtensionNames[index]);
    }
    if (!requestedBefore) {
      forwardedExtensions.push_back(kDepthExtension);
    }

    XrInstanceCreateInfo forwardedCreateInfo = *createInfo;
    forwardedCreateInfo.enabledExtensionCount = static_cast<uint32_t>(forwardedExtensions.size());
    forwardedCreateInfo.enabledExtensionNames = forwardedExtensions.data();
    XrApiLayerCreateInfo forwardedLayerInfo = *apiLayerInfo;
    forwardedLayerInfo.nextInfo = apiLayerInfo->nextInfo->next;

    const auto nextCreateApiLayerInstance = apiLayerInfo->nextInfo->nextCreateApiLayerInstance;
    const auto nextGetInstanceProcAddr = apiLayerInfo->nextInfo->nextGetInstanceProcAddr;
    marker("channel=instance status=create-forwarding requestedBefore=%s requestedBeforeCount=%u forwardedAfter=true forwardedAfterCount=%u augmented=%s",
           requestedBefore ? "true" : "false",
           createInfo->enabledExtensionCount,
           forwardedCreateInfo.enabledExtensionCount,
           requestedBefore ? "false" : "true");
    XrResult result = nextCreateApiLayerInstance(&forwardedCreateInfo, &forwardedLayerInfo, instance);
    const XrResult augmentedResult = result;
    bool extensionEnabled = result == XR_SUCCESS;
    bool fallbackWithoutAugmentation = false;

    if (result != XR_SUCCESS && !requestedBefore) {
      fallbackWithoutAugmentation = true;
      forwardedLayerInfo = *apiLayerInfo;
      forwardedLayerInfo.nextInfo = apiLayerInfo->nextInfo->next;
      result = nextCreateApiLayerInstance(createInfo, &forwardedLayerInfo, instance);
      extensionEnabled = false;
    }
    marker("channel=instance status=create-forwarded augmentedDownstreamResult=%d fallbackWithoutAugmentation=%s downstreamResult=%d",
           augmentedResult,
           fallbackWithoutAugmentation ? "true" : "false",
           result);

    if (result == XR_SUCCESS && *instance != nullptr) {
      std::lock_guard lock(gMutex);
      cleanupDepthLocked();
      gState = ProbeState{};
      gState.instance = *instance;
      gState.instanceOrdinal = gNextInstanceOrdinal++;
      gState.depthExtensionEnabled = extensionEnabled;
      resolveDispatchLocked(nextGetInstanceProcAddr, *instance);
      marker("channel=lifecycle status=instance-created instanceOrdinal=%llu requestedBefore=%s forwardedAfter=%s downstreamResult=%d depthExtensionEnabled=%s callableDepthFunctions=%s layerOwnedAcquireEnabled=false productionSidecar=false reusableDepth=false",
             static_cast<unsigned long long>(gState.instanceOrdinal),
             requestedBefore ? "true" : "false",
             extensionEnabled ? "true" : "false",
             result,
             gState.depthExtensionEnabled ? "true" : "false",
             gState.callableDepthFunctions ? "true" : "false");
    }
    return result;
  } catch (...) {
    marker("channel=instance status=create-failed stage=layer-exception");
    return XR_ERROR_RUNTIME_FAILURE;
  }
}

extern "C" XRAPI_ATTR XrResult XRAPI_CALL xrNegotiateLoaderApiLayerInterface(
    const XrNegotiateLoaderInfo* loaderInfo,
    const char* layerName,
    XrNegotiateApiLayerRequest* apiLayerRequest) {
  if (loaderInfo == nullptr || layerName == nullptr || apiLayerRequest == nullptr ||
      std::strcmp(layerName, kLayerName) != 0 ||
      loaderInfo->maxInterfaceVersion < XR_CURRENT_LOADER_API_LAYER_VERSION ||
      loaderInfo->minInterfaceVersion > XR_CURRENT_LOADER_API_LAYER_VERSION) {
    return XR_ERROR_INITIALIZATION_FAILED;
  }
  apiLayerRequest->layerInterfaceVersion = XR_CURRENT_LOADER_API_LAYER_VERSION;
  apiLayerRequest->layerApiVersion = std::min(loaderInfo->maxApiVersion, XR_CURRENT_API_VERSION);
  apiLayerRequest->getInstanceProcAddr = xrGetInstanceProcAddr;
  apiLayerRequest->createApiLayerInstance = xrCreateApiLayerInstance;
  marker("channel=loader status=layer-negotiated interfaceVersion=%u apiVersion=%llu",
         apiLayerRequest->layerInterfaceVersion,
         static_cast<unsigned long long>(apiLayerRequest->layerApiVersion));
  return XR_SUCCESS;
}

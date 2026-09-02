#pragma once

#include "spatial_depth_handoff_abi.h"
#include "spatial_depth_ring_vulkan.hpp"

#include <cstdint>

class DepthConsumerBridge {
 public:
  bool bind(
      const VulkanBindingSnapshot& binding,
      uint64_t sessionGeneration,
      DepthGpuHandoffVulkan* handoff);
  int32_t acquireLatest(
      uint64_t expectedDeviceToken,
      uint64_t expectedSessionGeneration,
      rq_depth_gpu_frame_v1* outFrame);
  int32_t releaseLease(uint64_t leaseId);
  void resetSession();
  uint64_t deviceToken() const;
  uint64_t sessionGeneration() const;

 private:
  int32_t validateExpected(
      uint64_t expectedDeviceToken,
      uint64_t expectedSessionGeneration) const;

  VulkanBindingSnapshot binding_{};
  DepthGpuHandoffVulkan* handoff_ = nullptr;
  uint64_t deviceToken_ = 0;
  uint64_t sessionGeneration_ = 0;
};

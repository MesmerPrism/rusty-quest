#include "spatial_depth_queue_broker.hpp"

#include <algorithm>
#include <cstdint>

bool DepthConsumerBridge::bind(
    const VulkanBindingSnapshot& binding,
    uint64_t sessionGeneration,
    DepthGpuHandoffVulkan* handoff) {
  resetSession();
  if (binding.instance == VK_NULL_HANDLE || binding.physicalDevice == VK_NULL_HANDLE ||
      binding.device == VK_NULL_HANDLE || handoff == nullptr || sessionGeneration == 0) {
    return false;
  }
  binding_ = binding;
  handoff_ = handoff;
  sessionGeneration_ = sessionGeneration;
  deviceToken_ = handoff_->deviceToken();
  return deviceToken_ != 0;
}

int32_t DepthConsumerBridge::validateExpected(
    uint64_t expectedDeviceToken,
    uint64_t expectedSessionGeneration) const {
  if (handoff_ == nullptr || deviceToken_ == 0 || sessionGeneration_ == 0) {
    return RQ_DEPTH_GPU_STATUS_NOT_READY;
  }
  if (expectedDeviceToken != deviceToken_) {
    return RQ_DEPTH_GPU_STATUS_DEVICE_MISMATCH;
  }
  if (expectedSessionGeneration != sessionGeneration_) {
    return RQ_DEPTH_GPU_STATUS_SESSION_MISMATCH;
  }
  return RQ_DEPTH_GPU_STATUS_OK;
}

int32_t DepthConsumerBridge::acquireLatest(
    uint64_t expectedDeviceToken,
    uint64_t expectedSessionGeneration,
    rq_depth_gpu_frame_v1* outFrame) {
  if (outFrame == nullptr || outFrame->struct_size != sizeof(*outFrame) ||
      outFrame->abi_version != RQ_DEPTH_GPU_ABI_V1) {
    return RQ_DEPTH_GPU_STATUS_ABI_MISMATCH;
  }
  const int32_t validation =
      validateExpected(expectedDeviceToken, expectedSessionGeneration);
  if (validation != RQ_DEPTH_GPU_STATUS_OK) {
    return validation;
  }
  DepthGpuLeaseSnapshot lease{};
  const int32_t status = handoff_->acquireLatest(
      expectedDeviceToken, expectedSessionGeneration, &lease);
  if (status != RQ_DEPTH_GPU_STATUS_OK) {
    return status;
  }

  const uint32_t structSize = outFrame->struct_size;
  const uint32_t abiVersion = outFrame->abi_version;
  *outFrame = {};
  outFrame->struct_size = structSize;
  outFrame->abi_version = abiVersion;
  outFrame->device_token = lease.deviceToken;
  outFrame->session_generation = lease.sessionGeneration;
  outFrame->generation = lease.generation;
  outFrame->frame_ordinal = lease.metadata.frameOrdinal;
  outFrame->lease_id = lease.leaseId;
  outFrame->image_handle =
      static_cast<uint64_t>(reinterpret_cast<uintptr_t>(lease.image));
  outFrame->image_view_handle =
      static_cast<uint64_t>(reinterpret_cast<uintptr_t>(lease.imageView));
  outFrame->width = lease.width;
  outFrame->height = lease.height;
  outFrame->layer_count = 2;
  outFrame->eye_order = RQ_DEPTH_GPU_EYE_ORDER_LAYER0_LEFT_LAYER1_RIGHT;
  outFrame->vk_format = VK_FORMAT_D16_UNORM;
  outFrame->vk_layout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
  outFrame->valid_mask = lease.metadata.validMask;
  outFrame->freshness_state = 1;
  outFrame->capture_time = lease.metadata.captureTime;
  outFrame->display_time = lease.metadata.displayTime;
  outFrame->render_view_display_time = lease.metadata.renderViewDisplayTime;
  outFrame->render_view_state_flags = lease.metadata.renderViewStateFlags;
  outFrame->near_z = lease.metadata.nearZ;
  outFrame->far_z = lease.metadata.farZ;
  outFrame->render_view_space_token = lease.metadata.renderViewSpaceToken;
  outFrame->render_view_space_generation = lease.metadata.renderViewSpaceGeneration;
  outFrame->render_view_session_generation = lease.metadata.renderViewSessionGeneration;
  outFrame->render_view_configuration_type = lease.metadata.renderViewConfigurationType;
  outFrame->render_view_locate_result = lease.metadata.renderViewLocateResult;
  outFrame->render_view_source = lease.metadata.renderViewSource;
  outFrame->reserved_metadata = lease.ringIndex;
  for (uint32_t eye = 0; eye < 2; ++eye) {
    const auto copyView = [](const DepthGpuViewMetadata& source, rq_depth_gpu_view_v1* target) {
      std::copy(std::begin(source.fov), std::end(source.fov), std::begin(target->fov));
      std::copy(
          std::begin(source.orientation),
          std::end(source.orientation),
          std::begin(target->orientation));
      std::copy(
          std::begin(source.position),
          std::end(source.position),
          std::begin(target->position));
    };
    copyView(lease.metadata.depthViews[eye], &outFrame->depth_views[eye]);
    copyView(lease.metadata.renderViews[eye], &outFrame->render_views[eye]);
  }
  return RQ_DEPTH_GPU_STATUS_OK;
}

int32_t DepthConsumerBridge::releaseLease(uint64_t leaseId) {
  if (handoff_ == nullptr || leaseId == 0) {
    return RQ_DEPTH_GPU_STATUS_INVALID_ARGUMENT;
  }
  return handoff_->releaseLease(leaseId);
}

void DepthConsumerBridge::resetSession() {
  binding_ = {};
  handoff_ = nullptr;
  deviceToken_ = 0;
  sessionGeneration_ = 0;
}

uint64_t DepthConsumerBridge::deviceToken() const {
  return deviceToken_;
}

uint64_t DepthConsumerBridge::sessionGeneration() const {
  return sessionGeneration_;
}

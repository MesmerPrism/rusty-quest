#pragma once
#include "gpu_hand_input.h"
#include "workload_backend.h"
#include <memory>

namespace rq::workload {
// One vec4 position/validity and one vec4 normal, in raw declared XR space.
struct HandVertex {
  float position[4];
  float normal[4];
};
static_assert(sizeof(HandVertex) == 32, "hand output ABI");
struct HandOutput {
  VkBuffer buffer = VK_NULL_HANDLE;
  VkDeviceSize bytes = 0;
  uint32_t baseCount = 0, graftCount = 0;
  uint32_t leftCount = 0, rightCount = 0;
  bool initialized = false;
  LiveHandPair input{};
};
class HandWorkload {
public:
  HandWorkload(const GpuContext &context, AAssetManager *assets);
  ~HandWorkload();
  HandOutput output() const;
  // Caller proves the previous submission using this single slot has retired.
  // No submission, fence reset, queue wait, or CPU dynamic vertices here.
  void record(VkCommandBuffer command, const FrameContext &frame,
              const LiveHandPair &input, bool drawHands, bool drawGrafts,
              float graftScale = 0.85f);
  void destroyAfterRetirement();

private:
  struct Impl;
  std::unique_ptr<Impl> impl_;
};
} // namespace rq::workload

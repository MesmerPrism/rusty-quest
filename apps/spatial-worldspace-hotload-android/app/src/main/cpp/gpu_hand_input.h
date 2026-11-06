#pragma once
#include <array>
#include <cstdint>
#include <memory>
#include <string>

namespace rq::workload {
struct HandPose {
  float translationRadius[4]{};
  float rotation[4]{0, 0, 0, 1};
};
struct CompactHand {
  std::array<HandPose, 21> joints{};
  std::array<float, 8> tipLengths{};
  bool active = false;
};
struct LiveHandPair {
  std::array<CompactHand, 2>
      hands{}; // fixed left/right, never promote the other hand
  uint64_t generation = 0, revision = 0, monotonicNs = 0;
  uint64_t sourceSessionGeneration =
      0; // explicit frontend identity; zero means unavailable
  int64_t xrTime = 0;
  int32_t referenceSpaceType = 0;
  uint32_t source = 1; // raw XR_EXT_hand_tracking
  uint32_t clockDomain =
      1; // CLOCK_MONOTONIC converted by XR_KHR_convert_timespec_time
};
static_assert(sizeof(HandPose) == 32, "public skinning Pose ABI");

// Owns trackers/space only. The SDK owns instance, session and frame lifecycle.
class HandInput {
public:
  HandInput();
  ~HandInput();
  bool attach(uint64_t instance, uint64_t session, uint64_t getProcAddr,
              uint64_t generation, int32_t referenceSpaceType, bool focused,
              uint64_t sourceSessionGeneration = 0);
  void setFocused(bool focused);
  LiveHandPair
  sample();      // current monotonic time, explicitly NOT SDK predicted time
  void detach(); // before SDK session invalidation; serialized against sample
  void
  close(); // terminal producer cleanup: detach and reject later attach races
  std::string status() const;

private:
  struct Impl;
  std::unique_ptr<Impl> impl_;
};
} // namespace rq::workload

#include "gpu_hand_input.h"
#define XR_NO_PROTOTYPES
#define XR_USE_TIMESPEC
#include <cmath>
#include <mutex>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <time.h>
#include <vector>

namespace rq::workload {
namespace {
constexpr int compactJoints[21] = {0,  1,  2,  3,  4,  6,  7,  8,  9,  11, 12,
                                   13, 14, 16, 17, 18, 19, 21, 22, 23, 24};
constexpr int distal[5] = {4, 9, 14, 19, 24}, tips[5] = {5, 10, 15, 20, 25};
bool positionValid(const XrHandJointLocationEXT &joint) {
  const auto required = XR_SPACE_LOCATION_POSITION_VALID_BIT |
                        XR_SPACE_LOCATION_POSITION_TRACKED_BIT;
  return (joint.locationFlags & required) == required &&
         std::isfinite(joint.pose.position.x) &&
         std::isfinite(joint.pose.position.y) &&
         std::isfinite(joint.pose.position.z);
}
bool compact(const XrHandJointLocationEXT *joints, CompactHand &out) {
  for (unsigned i = 0; i < 21; ++i) {
    const auto &j = joints[compactJoints[i]];
    const auto &q = j.pose.orientation;
    const float norm = q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w;
    if (!positionValid(j) ||
        !(j.locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) ||
        !std::isfinite(norm) || norm < 0.9f || norm > 1.1f ||
        !std::isfinite(j.radius) || j.radius < 0)
      return false;
    out.joints[i] = {
        {j.pose.position.x, j.pose.position.y, j.pose.position.z, j.radius},
        {q.x, q.y, q.z, q.w}};
  }
  for (unsigned i = 0; i < 5; ++i) {
    if (!positionValid(joints[tips[i]]))
      return false;
    const auto a = joints[distal[i]].pose.position,
               b = joints[tips[i]].pose.position;
    const float x = a.x - b.x, y = a.y - b.y, z = a.z - b.z;
    out.tipLengths[i] = std::sqrt(x * x + y * y + z * z);
    if (!std::isfinite(out.tipLengths[i]))
      return false;
  }
  out.active = true;
  return true;
}
} // namespace
struct HandInput::Impl {
  mutable std::mutex mutex;
  XrInstance instance = XR_NULL_HANDLE;
  XrSession session = XR_NULL_HANDLE;
  XrSpace space = XR_NULL_HANDLE;
  XrHandTrackerEXT trackers[2] = {XR_NULL_HANDLE, XR_NULL_HANDLE};
  PFN_xrGetInstanceProcAddr getProc = nullptr;
  PFN_xrCreateHandTrackerEXT createTracker = nullptr;
  PFN_xrDestroyHandTrackerEXT destroyTracker = nullptr;
  PFN_xrLocateHandJointsEXT locate = nullptr;
  PFN_xrCreateReferenceSpace createSpace = nullptr;
  PFN_xrDestroySpace destroySpace = nullptr;
  PFN_xrEnumerateReferenceSpaces enumerateSpaces = nullptr;
  PFN_xrConvertTimespecTimeToTimeKHR convertTime = nullptr;
  uint64_t generation = 0, revision = 0, sourceSessionGeneration = 0;
  int32_t referenceSpaceType = 0;
  bool focused = false, closed = false;
  std::string state = "detached";
  template <class T> bool resolve(const char *name, T &out) {
    PFN_xrVoidFunction fn = nullptr;
    if (XR_FAILED(getProc(instance, name, &fn)) || !fn)
      return false;
    out = reinterpret_cast<T>(fn);
    return true;
  }
  void clear() {
    focused = false;
    for (auto &tracker : trackers) {
      if (tracker && destroyTracker)
        destroyTracker(tracker);
      tracker = XR_NULL_HANDLE;
    }
    if (space && destroySpace)
      destroySpace(space);
    space = XR_NULL_HANDLE;
    instance = XR_NULL_HANDLE;
    session = XR_NULL_HANDLE;
    generation = 0;
    sourceSessionGeneration = 0;
    state = "detached";
  }
};
HandInput::HandInput() : impl_(std::make_unique<Impl>()) {}
HandInput::~HandInput() { close(); }
bool HandInput::attach(uint64_t instance, uint64_t session, uint64_t proc,
                       uint64_t generation, int32_t referenceSpaceType,
                       bool focused, uint64_t sourceSessionGeneration) {
  auto &p = *impl_;
  std::lock_guard<std::mutex> lock(p.mutex);
  if (p.closed) {
    p.state = "closed";
    return false;
  }
  if (p.instance == reinterpret_cast<XrInstance>(instance) &&
      p.session == reinterpret_cast<XrSession>(session) &&
      p.generation == generation &&
      p.sourceSessionGeneration == sourceSessionGeneration &&
      p.referenceSpaceType == referenceSpaceType && p.space) {
    p.focused = focused;
    return true;
  }
  p.clear();
  if (!instance || !session || !proc || !generation) {
    p.state = "invalid-borrowed-context";
    return false;
  }
  p.instance = reinterpret_cast<XrInstance>(instance);
  p.session = reinterpret_cast<XrSession>(session);
  p.getProc = reinterpret_cast<PFN_xrGetInstanceProcAddr>(proc);
  const bool resolved =
      p.resolve("xrCreateHandTrackerEXT", p.createTracker) &&
      p.resolve("xrDestroyHandTrackerEXT", p.destroyTracker) &&
      p.resolve("xrLocateHandJointsEXT", p.locate) &&
      p.resolve("xrCreateReferenceSpace", p.createSpace) &&
      p.resolve("xrDestroySpace", p.destroySpace) &&
      p.resolve("xrEnumerateReferenceSpaces", p.enumerateSpaces) &&
      p.resolve("xrConvertTimespecTimeToTimeKHR", p.convertTime);
  if (!resolved) {
    p.clear();
    p.state = "required-xr-functions-unavailable";
    return false;
  }
  uint32_t count = 0;
  if (XR_FAILED(p.enumerateSpaces(p.session, 0, &count, nullptr)) ||
      count == 0 || count > 64) {
    p.clear();
    p.state = "reference-spaces-unavailable";
    return false;
  }
  std::vector<XrReferenceSpaceType> spaces(count);
  if (XR_FAILED(p.enumerateSpaces(p.session, count, &count, spaces.data())) ||
      count > spaces.size()) {
    p.clear();
    p.state = "reference-spaces-invalid";
    return false;
  }
  bool found = false;
  for (uint32_t i = 0; i < count; ++i)
    found |= int32_t(spaces[i]) == referenceSpaceType;
  if (!found) {
    p.clear();
    p.state = "requested-reference-space-unsupported";
    return false;
  }
  XrReferenceSpaceCreateInfo spaceInfo{};
  spaceInfo.type = XR_TYPE_REFERENCE_SPACE_CREATE_INFO;
  spaceInfo.referenceSpaceType =
      static_cast<XrReferenceSpaceType>(referenceSpaceType);
  spaceInfo.poseInReferenceSpace.orientation.w = 1;
  if (XR_FAILED(p.createSpace(p.session, &spaceInfo, &p.space))) {
    p.clear();
    p.state = "create-reference-space-failed";
    return false;
  }
  for (unsigned hand = 0; hand < 2; ++hand) {
    XrHandTrackerCreateInfoEXT info{};
    info.type = XR_TYPE_HAND_TRACKER_CREATE_INFO_EXT;
    info.hand = hand == 0 ? XR_HAND_LEFT_EXT : XR_HAND_RIGHT_EXT;
    info.handJointSet = XR_HAND_JOINT_SET_DEFAULT_EXT;
    if (XR_FAILED(p.createTracker(p.session, &info, &p.trackers[hand]))) {
      p.clear();
      p.state = "create-hand-tracker-failed";
      return false;
    }
  }
  p.generation = generation;
  p.sourceSessionGeneration = sourceSessionGeneration;
  p.referenceSpaceType = referenceSpaceType;
  p.focused = focused;
  p.state = "attached-current-monotonic";
  return true;
}
void HandInput::setFocused(bool focused) {
  std::lock_guard<std::mutex> lock(impl_->mutex);
  impl_->focused = focused;
}
LiveHandPair HandInput::sample() {
  auto &p = *impl_;
  std::lock_guard<std::mutex> lock(p.mutex);
  LiveHandPair out;
  out.generation = p.generation;
  out.referenceSpaceType = p.referenceSpaceType;
  out.sourceSessionGeneration = p.sourceSessionGeneration;
  out.revision = ++p.revision;
  if (!p.focused || !p.space) {
    p.state = p.space ? "not-focused" : "detached";
    return out;
  }
  timespec now{};
  XrTime time = 0;
  if (clock_gettime(CLOCK_MONOTONIC, &now) != 0 ||
      XR_FAILED(p.convertTime(p.instance, &now, &time)) || time <= 0) {
    p.state = "clock-conversion-failed";
    return out;
  }
  out.monotonicNs =
      uint64_t(now.tv_sec) * 1000000000ull + uint64_t(now.tv_nsec);
  out.xrTime = time;
  for (unsigned hand = 0; hand < 2; ++hand) {
    XrHandJointLocationEXT joints[XR_HAND_JOINT_COUNT_EXT]{};
    XrHandJointLocationsEXT locations{};
    locations.type = XR_TYPE_HAND_JOINT_LOCATIONS_EXT;
    locations.jointCount = XR_HAND_JOINT_COUNT_EXT;
    locations.jointLocations = joints;
    XrHandJointsLocateInfoEXT info{};
    info.type = XR_TYPE_HAND_JOINTS_LOCATE_INFO_EXT;
    info.baseSpace = p.space;
    info.time = time;
    if (XR_SUCCEEDED(p.locate(p.trackers[hand], &info, &locations)) &&
        locations.isActive && locations.jointCount == XR_HAND_JOINT_COUNT_EXT) {
      CompactHand candidate;
      if (compact(joints, candidate))
        out.hands[hand] = candidate;
    }
  }
  p.state = "sampled-current-monotonic";
  return out;
}
void HandInput::detach() {
  std::lock_guard<std::mutex> lock(impl_->mutex);
  impl_->clear();
}
void HandInput::close() {
  std::lock_guard<std::mutex> lock(impl_->mutex);
  impl_->closed = true;
  impl_->clear();
  impl_->state = "closed";
}
std::string HandInput::status() const {
  std::lock_guard<std::mutex> lock(impl_->mutex);
  return impl_->state +
         " source=raw-XR_EXT_hand_tracking sourceReferenceSpace=" +
         std::to_string(impl_->referenceSpaceType) +
         " clockDomain=CLOCK_MONOTONIC-to-XrTime-KHR predictedTime=false "
         "generation=" +
         std::to_string(impl_->generation) + " sourceSessionGeneration=" +
         std::to_string(impl_->sourceSessionGeneration);
}
} // namespace rq::workload

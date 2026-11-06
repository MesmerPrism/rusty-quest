// Host test of the real bridge through a fake OpenXR function table. No device.
#include "../../main/cpp/gpu_hand_input.cpp"
#include <cassert>
#include <cstring>
#include <iostream>
namespace {
bool leftActive = true, rightActive = true, failClock = false,
     badOrientation = false;
int trackerCreates = 0, trackerDestroys = 0, spaceCreates = 0,
    spaceDestroys = 0, locates = 0;
constexpr int32_t floorSpace = 1000426000;
XrResult XRAPI_CALL
createSpace(XrSession, const XrReferenceSpaceCreateInfo *info, XrSpace *out) {
  assert(int32_t(info->referenceSpaceType) == floorSpace);
  ++spaceCreates;
  *out = reinterpret_cast<XrSpace>(30);
  return XR_SUCCESS;
}
XrResult XRAPI_CALL destroySpace(XrSpace) {
  ++spaceDestroys;
  return XR_SUCCESS;
}
XrResult XRAPI_CALL enumerateSpaces(XrSession, uint32_t capacity,
                                    uint32_t *count,
                                    XrReferenceSpaceType *spaces) {
  *count = 1;
  if (capacity)
    spaces[0] = static_cast<XrReferenceSpaceType>(floorSpace);
  return XR_SUCCESS;
}
XrResult XRAPI_CALL createTracker(XrSession,
                                  const XrHandTrackerCreateInfoEXT *info,
                                  XrHandTrackerEXT *out) {
  ++trackerCreates;
  *out = reinterpret_cast<XrHandTrackerEXT>(
      info->hand == XR_HAND_LEFT_EXT ? 40 : 41);
  return XR_SUCCESS;
}
XrResult XRAPI_CALL destroyTracker(XrHandTrackerEXT) {
  ++trackerDestroys;
  return XR_SUCCESS;
}
XrResult XRAPI_CALL convertTime(XrInstance, const timespec *time, XrTime *out) {
  if (failClock)
    return XR_ERROR_TIME_INVALID;
  *out = XrTime(time->tv_sec) * 1000000000 + time->tv_nsec + 123;
  return XR_SUCCESS;
}
XrResult XRAPI_CALL locate(XrHandTrackerEXT tracker,
                           const XrHandJointsLocateInfoEXT *info,
                           XrHandJointLocationsEXT *out) {
  ++locates;
  assert(info->baseSpace == reinterpret_cast<XrSpace>(30));
  assert(info->time > 123);
  const bool right = tracker == reinterpret_cast<XrHandTrackerEXT>(41);
  out->isActive = right ? rightActive : leftActive;
  for (uint32_t i = 0; i < out->jointCount; ++i) {
    auto &j = out->jointLocations[i];
    j.locationFlags = XR_SPACE_LOCATION_POSITION_VALID_BIT |
                      XR_SPACE_LOCATION_ORIENTATION_VALID_BIT |
                      XR_SPACE_LOCATION_POSITION_TRACKED_BIT;
    j.pose.position = {right ? 3.0f : 1.0f, float(i) * 0.01f, -0.5f};
    j.pose.orientation = {0, 0, 0, badOrientation ? 0.0f : 1.0f};
    j.radius = .01f;
  }
  return XR_SUCCESS;
}
XrResult XRAPI_CALL getProc(XrInstance, const char *name,
                            PFN_xrVoidFunction *out) {
#define MATCH(n, f)                                                            \
  if (std::strcmp(name, n) == 0) {                                             \
    *out = reinterpret_cast<PFN_xrVoidFunction>(f);                            \
    return XR_SUCCESS;                                                         \
  }
  MATCH("xrCreateReferenceSpace", createSpace)
  MATCH("xrDestroySpace", destroySpace)
      MATCH("xrEnumerateReferenceSpaces", enumerateSpaces)
          MATCH("xrCreateHandTrackerEXT", createTracker)
              MATCH("xrDestroyHandTrackerEXT", destroyTracker)
                  MATCH("xrLocateHandJointsEXT", locate)
                      MATCH("xrConvertTimespecTimeToTimeKHR", convertTime)
#undef MATCH
                          return XR_ERROR_FUNCTION_UNSUPPORTED;
}
} // namespace
int main() {
  rq::workload::HandInput input;
  assert(!input.attach(0, 2, reinterpret_cast<uint64_t>(getProc), 1, floorSpace,
                       true));
  assert(!input.attach(1, 2, reinterpret_cast<uint64_t>(getProc), 1, 2,
                       true)); // no silent LOCAL fallback
  assert(trackerCreates == 0 && spaceCreates == 0);
  assert(input.attach(1, 2, reinterpret_cast<uint64_t>(getProc), 1, floorSpace,
                      true));
  auto frame = input.sample();
  assert(frame.hands[0].active && frame.hands[1].active);
  assert(frame.generation == 1 && frame.xrTime > 0 && frame.monotonicNs > 0);
  assert(frame.hands[0].joints[0].translationRadius[0] == 1 &&
         frame.hands[1].joints[0].translationRadius[0] == 3);
  assert(frame.hands[1].joints[5].translationRadius[1] ==
         0.06f); // XR index6 maps compact5
  leftActive = false;
  frame = input.sample();
  assert(!frame.hands[0].active && frame.hands[1].active);
  assert(frame.hands[0].joints[0].translationRadius[0] ==
         0); // no retained/replayed/promoted pose
  const int before = locates;
  input.setFocused(false);
  frame = input.sample();
  assert(!frame.hands[1].active && locates == before);
  input.setFocused(true);
  failClock = true;
  frame = input.sample();
  assert(!frame.hands[1].active && locates == before);
  failClock = false;
  badOrientation = true;
  frame = input.sample();
  assert(!frame.hands[1].active);
  badOrientation = false;
  assert(input.attach(1, 2, reinterpret_cast<uint64_t>(getProc), 1, floorSpace,
                      true));
  assert(trackerCreates == 2);
  assert(input.attach(1, 3, reinterpret_cast<uint64_t>(getProc), 2, floorSpace,
                      true));
  assert(trackerDestroys == 2 && spaceDestroys == 1);
  frame = input.sample();
  assert(frame.generation == 2 && frame.hands[1].active);
  input.detach();
  assert(trackerDestroys == 4 && spaceDestroys == 2);
  frame = input.sample();
  assert(!frame.hands[0].active && !frame.hands[1].active &&
         frame.generation == 0);
  input.close();
  assert(!input.attach(1, 3, reinterpret_cast<uint64_t>(getProc), 3, floorSpace,
                       true));
  assert(trackerCreates == 4);
  std::cout << "hand bridge lifecycle/asymmetry/clock/validity checks passed\n";
}

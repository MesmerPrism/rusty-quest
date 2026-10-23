#include "../../main/cpp/gpu_hand_math.h"
#include <cassert>
#include <cstddef>
#include <iostream>
#include <limits>
using namespace rq::workload;
int main() {
  static_assert(offsetof(hand_math::GraftParams, targets) == 32);
  static_assert(offsetof(hand_math::GraftParams, rotations) == 112);
  CompactHand source, target;
  source.active = target.active = true;
  source.joints[0].translationRadius[0] = 1;
  source.joints[1].translationRadius[3] = .02f;
  const int distal[5] = {4, 8, 12, 16, 20};
  for (int i = 0; i < 5; ++i) {
    target.joints[distal[i]].translationRadius[0] = 10.0f + i;
    target.joints[distal[i]].translationRadius[3] = .005f;
    target.tipLengths[i] = .03f + .01f * i;
  }
  assert(hand_math::validHand(source) && hand_math::validHand(target));
  const auto params = hand_math::grafts(source, target, .85f);
  assert(params.source[0] == 1 && params.source[3] == .02f);
  for (int i = 0; i < 5; ++i) {
    assert(std::abs(params.targets[i][0] - (10.0f + i)) < 1e-6f);
    assert(std::abs(params.targets[i][2] + target.tipLengths[i]) < 1e-6f);
    assert(std::abs(params.targets[i][3] - .2125f) < 1e-6f);
    assert(params.rotations[i][3] == 1);
  }
  const float q[4] = {0, 0, .7071067812f, .7071067812f};
  const auto rotated = hand_math::rotate(q, {1, 0, 0});
  assert(std::abs(rotated[0]) < 1e-6f && std::abs(rotated[1] - 1) < 1e-6f);
  source.active = false;
  assert(!hand_math::validHand(source));
  source.active = true;
  source.joints[0].rotation[3] = 0;
  assert(!hand_math::validHand(source));
  source.joints[0].rotation[3] = 1;
  source.tipLengths[0] = std::numeric_limits<float>::quiet_NaN();
  assert(!hand_math::validHand(source));
  std::cout << "actual graft ABI/math and invalid-input checks passed\n";
}

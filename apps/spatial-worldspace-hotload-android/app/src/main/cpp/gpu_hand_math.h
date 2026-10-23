#pragma once
#include "gpu_hand_input.h"
#include <algorithm>
#include <array>
#include <cmath>
namespace rq::workload::hand_math {
struct GraftParams {
  float source[4], orientation[4], targets[5][4], rotations[5][4];
};
static_assert(sizeof(GraftParams) == 192, "native graft ABI");
inline bool finitePose(const HandPose &p) {
  float n = 0;
  for (float f : p.translationRadius)
    if (!std::isfinite(f))
      return false;
  for (float f : p.rotation) {
    if (!std::isfinite(f))
      return false;
    n += f * f;
  }
  return n > 0.9f && n < 1.1f;
}
inline bool validHand(const CompactHand &h) {
  if (!h.active)
    return false;
  for (const auto &p : h.joints)
    if (!finitePose(p))
      return false;
  for (float f : h.tipLengths)
    if (!std::isfinite(f) || f < 0)
      return false;
  return true;
}
inline std::array<float, 3> rotate(const float *q,
                                   const std::array<float, 3> &p) {
  const float inv =
      1 / std::sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
  const float x = q[0] * inv, y = q[1] * inv, z = q[2] * inv, w = q[3] * inv;
  const std::array<float, 3> uv{y * p[2] - z * p[1], z * p[0] - x * p[2],
                                x * p[1] - y * p[0]};
  return {p[0] + 2 * w * uv[0] + 2 * (y * uv[2] - z * uv[1]),
          p[1] + 2 * w * uv[1] + 2 * (z * uv[0] - x * uv[2]),
          p[2] + 2 * w * uv[2] + 2 * (x * uv[1] - y * uv[0])};
}
inline float positive(float v, float fallback) {
  return std::isfinite(v) && v > 0.0001f ? v : fallback;
}
inline GraftParams grafts(const CompactHand &source, const CompactHand &target,
                          float scale) {
  GraftParams out{};
  const auto &palm = source.joints[0];
  const float radius = positive(source.joints[1].translationRadius[3], 0.022f);
  std::copy_n(palm.translationRadius, 3, out.source);
  out.source[3] = radius;
  std::copy_n(palm.rotation, 4, out.orientation);
  constexpr int distal[5] = {4, 8, 12, 16, 20};
  scale = std::clamp(positive(scale, 1), 0.10f, 2.0f);
  for (int i = 0; i < 5; ++i) {
    const auto &p = target.joints[distal[i]];
    const auto offset = rotate(p.rotation, {0, 0, -target.tipLengths[i]});
    for (int j = 0; j < 3; ++j)
      out.targets[i][j] = p.translationRadius[j] + offset[j];
    out.targets[i][3] =
        std::clamp(positive(p.translationRadius[3], 0.006f) / radius * scale,
                   0.045f, 0.42f);
    std::copy_n(p.rotation, 4, out.rotations[i]);
  }
  return out;
}
} // namespace rq::workload::hand_math

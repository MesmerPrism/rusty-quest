#version 450

layout(location = 0) in vec2 inMaskUv;
layout(location = 1) in vec4 inColor;
layout(location = 2) in vec4 inParticleParams;
layout(location = 0) out vec4 outColor;

#ifndef PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE
#define PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE 1
#endif

#define PRIVATE_PARTICLE_MASK_MODE_PROCEDURAL 0
#define PRIVATE_PARTICLE_MASK_MODE_ARRAY_NEAREST 1
#define PRIVATE_PARTICLE_MASK_MODE_ARRAY_BLEND 2

#if PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE == PRIVATE_PARTICLE_MASK_MODE_ARRAY_NEAREST \
    || PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE == PRIVATE_PARTICLE_MASK_MODE_ARRAY_BLEND
layout(set = 0, binding = 6) uniform sampler2DArray u_mask_array;
#endif

layout(push_constant) uniform SurfacePrivateParticlePush {
  vec4 params0;
  vec4 params1;
  vec4 transparencyParams;
  vec4 tracerParams;
  vec4 worldCenterScale;
  vec4 panelRightWidth;
  vec4 panelUpHeight;
  vec4 forwardDistance;
} pc;

const float TAU = 6.28318530717958647692;
const float AKD_RING_EDGE_WIDTH = 0.015;
const float AKD_RING_OUTER_FEATHER = 0.06;
const float AKD_RING_RADIUS = 0.32;
const float AKD_RING_THICKNESS = 0.03;
const float AKD_RING_DUAL_OFFSET_RADIANS = 3.14159265358979323846;
const float PRIVATE_PARTICLE_MASK_ALPHA_CUTOFF = 0.001;

float seg_dist(vec2 p, vec2 a, vec2 b) {
  vec2 pa = p - a;
  vec2 ba = b - a;
  float h = clamp(dot(pa, ba) / max(dot(ba, ba), 0.000001), 0.0, 1.0);
  return length(pa - ba * h);
}

float morph_factor(float t) {
  t = min(clamp(t, 0.0, 1.0), 0.99902344);
  float t4 = t * 4.0;
  float seg = floor(t4);
  float fracPart = t4 - seg;
  float slope = mix(1.0, -1.0, step(2.0, seg));
  float offset = mix(
      mix(0.0, 1.0, step(1.0, seg)),
      mix(2.0, 1.0, step(3.0, seg)),
      step(2.0, seg));
  return offset + slope * fracPart;
}

vec2 morphed_arc_point(float a0, float a1, float s, float m, float radius) {
  float th = a0 + s * (a1 - a0);
  vec2 circlePoint = radius * vec2(cos(th), sin(th));
  vec2 aPoint = radius * vec2(cos(a0), sin(a0));
  vec2 bPoint = radius * vec2(cos(a1), sin(a1));
  vec2 chordPoint = mix(aPoint, bPoint, s);
  return circlePoint + m * (chordPoint - circlePoint);
}

vec2 rotate_around_center(vec2 p, float angle) {
  vec2 center = vec2(0.5);
  vec2 local = p - center;
  float s = sin(angle);
  float c = cos(angle);
  return center + vec2(local.x * c - local.y * s, local.x * s + local.y * c);
}

float morphed_ring_dist_single(vec2 p, float phase01) {
  vec2 center = vec2(0.5);
  float m = morph_factor(phase01);
  float safeThickness = min(AKD_RING_THICKNESS, AKD_RING_RADIUS * 0.99);
  float midRadius = max(AKD_RING_RADIUS - 0.5 * safeThickness, 0.0001);
  float dMin = 999.0;
  for (int arc = 0; arc < 3; ++arc) {
    float a0 = float(arc) * (TAU / 3.0);
    float a1 = float(arc + 1) * (TAU / 3.0);
    vec2 prev = center + morphed_arc_point(a0, a1, 0.0, m, midRadius);
    for (int i = 1; i <= 8; ++i) {
      float s = float(i) / 8.0;
      vec2 cur = center + morphed_arc_point(a0, a1, s, m, midRadius);
      dMin = min(dMin, seg_dist(p, prev, cur));
      prev = cur;
    }
  }
  return dMin;
}

float morphed_ring_dist(vec2 p, float phase01) {
  float fullOffset = AKD_RING_DUAL_OFFSET_RADIANS * abs(phase01 * 2.0 - 1.0);
  float halfOffset = 0.5 * fullOffset;
  float dA = morphed_ring_dist_single(rotate_around_center(p, -halfOffset), phase01);
  float dB = morphed_ring_dist_single(rotate_around_center(p, halfOffset), phase01);
  return min(dA, dB);
}

float procedural_morphed_ring_alpha(vec2 uv, float frame01) {
  float d = morphed_ring_dist(uv, frame01);
  float aa = max(fwidth(d), 0.0001);
  float core = 1.0 - smoothstep(AKD_RING_EDGE_WIDTH, AKD_RING_EDGE_WIDTH + aa, d);
  float feather = 1.0 - smoothstep(
      AKD_RING_EDGE_WIDTH + aa,
      AKD_RING_EDGE_WIDTH + aa + AKD_RING_OUTER_FEATHER,
      d);
  return max(core, feather);
}

#if PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE == PRIVATE_PARTICLE_MASK_MODE_ARRAY_NEAREST \
    || PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE == PRIVATE_PARTICLE_MASK_MODE_ARRAY_BLEND
float texture_array_alpha_nearest(vec2 uv, float frame01) {
  int layers = max(textureSize(u_mask_array, 0).z, 1);
  float frame = clamp(frame01, 0.0, 0.99902344) * float(layers - 1);
  float layer = floor(frame + 0.5);
  return texture(u_mask_array, vec3(uv, layer)).r;
}

float texture_array_alpha_blend(vec2 uv, float frame01) {
  int layers = max(textureSize(u_mask_array, 0).z, 1);
  float frame = clamp(frame01, 0.0, 0.99902344) * float(layers - 1);
  float layer0 = floor(frame);
  float layer1 = min(layer0 + 1.0, float(layers - 1));
  float mix01 = frame - layer0;
  float alpha0 = texture(u_mask_array, vec3(uv, layer0)).r;
  float alpha1 = texture(u_mask_array, vec3(uv, layer1)).r;
  return mix(alpha0, alpha1, mix01);
}
#endif

void main() {
  float frame01 = clamp(inParticleParams.z, 0.0, 0.99902344);
#if PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE == PRIVATE_PARTICLE_MASK_MODE_ARRAY_NEAREST
  float mask = texture_array_alpha_nearest(inMaskUv, frame01);
#elif PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE == PRIVATE_PARTICLE_MASK_MODE_ARRAY_BLEND
  float mask = texture_array_alpha_blend(inMaskUv, frame01);
#else
  float mask = procedural_morphed_ring_alpha(inMaskUv, frame01);
#endif
  bool visibleMaskPixel = mask >= PRIVATE_PARTICLE_MASK_ALPHA_CUTOFF;
  if (!visibleMaskPixel) {
    discard;
  }

  float opacity = clamp(pc.transparencyParams.x, 0.0, 4.0);
  float outputAlphaScale = clamp(pc.transparencyParams.y, 0.0, 4.0);
  float rgbAlphaCoupling = clamp(pc.transparencyParams.w, 0.0, 1.0);
  float coverageAlpha = clamp(mask * inColor.a * opacity * inParticleParams.w, 0.0, 1.0);
  vec3 baseRgb = clamp(inColor.rgb, vec3(0.0), vec3(1.0));
  vec3 rgb = baseRgb * mix(1.0, coverageAlpha, rgbAlphaCoupling);
  float outputAlpha = clamp(coverageAlpha * outputAlphaScale, 0.0, 1.0);
  outColor = vec4(rgb * outputAlpha, outputAlpha);
}

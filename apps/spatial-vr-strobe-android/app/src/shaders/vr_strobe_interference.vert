#version 430
// Owned by the standalone Spatial VR Strobe application module.
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable

#include <data/shaders/common.glsl>
#include <data/shaders/app2vertex.glsl>

#define PATTERN_FIELDS(P) \
  vec4 P##0##A; vec4 P##0##B; vec4 P##0##C; vec4 P##0##D; vec4 P##0##E; \
  vec4 P##1##A; vec4 P##1##B; vec4 P##1##C; vec4 P##1##D; vec4 P##1##E; \
  vec4 P##2##A; vec4 P##2##B; vec4 P##2##C; vec4 P##2##D; vec4 P##2##E; \
  vec4 P##3##A; vec4 P##3##B; vec4 P##3##C; vec4 P##3##D; vec4 P##3##E; \
  vec4 P##4##A; vec4 P##4##B; vec4 P##4##C; vec4 P##4##D; vec4 P##4##E; \
  vec4 P##5##A; vec4 P##5##B; vec4 P##5##C; vec4 P##5##D; vec4 P##5##E; \
  vec4 P##6##A; vec4 P##6##B; vec4 P##6##C; vec4 P##6##D; vec4 P##6##E; \
  vec4 P##7##A; vec4 P##7##B; vec4 P##7##C; vec4 P##7##D; vec4 P##7##E;

layout(std140, set = 3, binding = 0) uniform MaterialUniform {
  vec4 carrierShape;
  vec4 depthDeformation;
  vec4 modeTime;
  vec4 color1;
  vec4 color2;
  vec4 color3;
  vec4 colorAnim;
  vec4 global0;
  vec4 global1;
  vec4 global2;
  vec4 post0;
  vec4 effects0;
  vec4 effects1;
  vec4 strobe0;
  vec4 strobe1;
  vec4 strobe2;
  vec4 fixationColor;
  vec4 patternCounts;
  PATTERN_FIELDS(stripe)
  PATTERN_FIELDS(ripple)
  PATTERN_FIELDS(ray)
  PATTERN_FIELDS(perlin)
} g_MaterialUniform;

layout(location = 0) out struct {
  vec3 objectPosition;
} vertexOut;

const float TAU = 6.28318530718;

#define LOAD_CASE(P, N) \
  if (index == N) { \
    a = g_MaterialUniform.P##N##A; b = g_MaterialUniform.P##N##B; \
    c = g_MaterialUniform.P##N##C; d = g_MaterialUniform.P##N##D; \
    e = g_MaterialUniform.P##N##E; return; \
  }

#define LOAD_FUNCTION(NAME, P) \
  void NAME(int index, out vec4 a, out vec4 b, out vec4 c, out vec4 d, out vec4 e) { \
    a = vec4(0.0); b = vec4(0.0); c = vec4(0.0); d = vec4(0.0); e = vec4(0.0); \
    LOAD_CASE(P, 0) LOAD_CASE(P, 1) LOAD_CASE(P, 2) LOAD_CASE(P, 3) \
    LOAD_CASE(P, 4) LOAD_CASE(P, 5) LOAD_CASE(P, 6) LOAD_CASE(P, 7) \
  }

LOAD_FUNCTION(loadDepthStripe, stripe)
LOAD_FUNCTION(loadDepthRipple, ripple)
LOAD_FUNCTION(loadDepthRay, ray)
LOAD_FUNCTION(loadDepthPerlin, perlin)

float depthHash31(vec3 point) {
  point = fract(point * 0.3183099 + 0.1);
  point *= 17.0;
  return fract(point.x * point.y * point.z * (point.x + point.y + point.z));
}

float depthNoise3D(vec3 point) {
  vec3 cell = floor(point);
  vec3 fraction = fract(point);
  fraction = fraction * fraction * (3.0 - 2.0 * fraction);
  return mix(
      mix(
          mix(depthHash31(cell), depthHash31(cell + vec3(1, 0, 0)), fraction.x),
          mix(depthHash31(cell + vec3(0, 1, 0)), depthHash31(cell + vec3(1, 1, 0)), fraction.x),
          fraction.y),
      mix(
          mix(depthHash31(cell + vec3(0, 0, 1)), depthHash31(cell + vec3(1, 0, 1)), fraction.x),
          mix(depthHash31(cell + vec3(0, 1, 1)), depthHash31(cell + vec3(1, 1, 1)), fraction.x),
          fraction.y),
      fraction.z);
}

vec2 depthRotatePoint(vec2 point, float angle, vec2 pivot) {
  float sine = sin(angle);
  float cosine = cos(angle);
  point -= pivot;
  return vec2(point.x * cosine - point.y * sine, point.x * sine + point.y * cosine) + pivot;
}

vec2 depthMovingOffset(float timeSeconds, float seed, float amount) {
  return vec2(
      sin(timeSeconds * 0.5 + seed) * cos(timeSeconds * 0.3 + seed * 2.0),
      cos(timeSeconds * 0.4 + seed * 3.0) * sin(timeSeconds * 0.6 + seed * 1.5)) * amount;
}

float depthFrequencyLimit() {
  float fullDetailLimit = max(g_MaterialUniform.depthDeformation.z, 1.0);
  float maxDisplacementLimit =
      clamp(g_MaterialUniform.depthDeformation.w, 1.0, fullDetailLimit);
  float amplitudeBlend = smoothstep(
      0.05,
      max(g_MaterialUniform.carrierShape.w * 0.5, 0.051),
      g_MaterialUniform.depthDeformation.y);
  return mix(fullDetailLimit, maxDisplacementLimit, amplitudeBlend);
}

float depthLocalFrequency(float requested, float globalScale, float limit) {
  return min(max(abs(requested), 0.001), limit / max(abs(globalScale), 0.001));
}

// A deliberately low-pass geometric witness of the interference signal. The
// fragment shader remains the visual authority for fine patterns, distortion,
// post noise, trail, and blur; none of those are reevaluated per fragment here.
float depthInterferenceSignal(vec2 rawUv, float timeSeconds) {
  vec2 shake = vec2(
      sin(timeSeconds * g_MaterialUniform.global1.w),
      cos(timeSeconds * g_MaterialUniform.global1.w * 1.3)) * g_MaterialUniform.global1.z;
  vec2 generationShift =
      vec2(g_MaterialUniform.modeTime.z, g_MaterialUniform.modeTime.z * -0.731);
  vec2 uv = rawUv + generationShift + g_MaterialUniform.global1.xy + shake;
  uv.x += uv.y * g_MaterialUniform.global0.y;
  uv.y += uv.x * g_MaterialUniform.global0.z;
  float globalScale = g_MaterialUniform.global0.x;
  uv *= globalScale;
  uv = depthRotatePoint(uv, timeSeconds * g_MaterialUniform.global0.w, vec2(0.0));
  float frequencyLimit = depthFrequencyLimit();
  float signal = 0.0;

  for (int index = 0; index < 8; ++index) {
    if (index >= int(g_MaterialUniform.patternCounts.x + 0.5)) break;
    vec4 a; vec4 b; vec4 c; vec4 d; vec4 e;
    loadDepthStripe(index, a, b, c, d, e);
    if (a.x < 0.5) continue;
    vec2 point = depthRotatePoint(uv, -(d.z + timeSeconds * d.w), b.xy) - b.xy;
    float frequency = depthLocalFrequency(a.z, globalScale, frequencyLimit);
    float value = sin(point.x * frequency - timeSeconds * a.w);
    float fade = 1.0;
    if (e.x > 0.0) {
      float distanceInCycles = abs(point.x * frequency) / TAU;
      fade = 1.0 - smoothstep(e.x * 0.5, e.x, distanceInCycles);
    }
    signal += value * fade * a.y;
  }

  for (int index = 0; index < 8; ++index) {
    if (index >= int(g_MaterialUniform.patternCounts.y + 0.5)) break;
    vec4 a; vec4 b; vec4 c; vec4 d; vec4 e;
    loadDepthRipple(index, a, b, c, d, e);
    if (a.x < 0.5) continue;
    vec2 pivot = b.xy + depthMovingOffset(timeSeconds, float(index) * 10.0, e.w);
    vec2 point = depthRotatePoint(uv, -timeSeconds * d.w, e.yz);
    float radius = length(point - pivot);
    signal +=
        sin(radius * depthLocalFrequency(a.z, globalScale, frequencyLimit) - timeSeconds * a.w) * a.y;
  }

  for (int index = 0; index < 8; ++index) {
    if (index >= int(g_MaterialUniform.patternCounts.z + 0.5)) break;
    vec4 a; vec4 b; vec4 c; vec4 d; vec4 e;
    loadDepthRay(index, a, b, c, d, e);
    if (a.x < 0.5) continue;
    vec2 pivot = b.xy + depthMovingOffset(timeSeconds, float(index) * 20.0, e.w);
    vec2 point = depthRotatePoint(uv, -timeSeconds * d.w, e.yz);
    vec2 delta = point - pivot;
    float rayCount = floor(min(max(abs(a.z), 1.0), frequencyLimit * 2.0));
    signal += sin(atan(delta.y, delta.x) * rayCount - timeSeconds * a.w) * a.y;
  }

  for (int index = 0; index < 8; ++index) {
    if (index >= int(g_MaterialUniform.patternCounts.w + 0.5)) break;
    vec4 a; vec4 b; vec4 c; vec4 d; vec4 e;
    loadDepthPerlin(index, a, b, c, d, e);
    if (a.x < 0.5) continue;
    vec2 point = uv - b.xy;
    float scale = depthLocalFrequency(a.z, globalScale, frequencyLimit);
    float value = depthNoise3D(vec3(point * scale, b.z + timeSeconds * a.w));
    signal += (value * 2.0 - 1.0) * a.y;
  }

  if (g_MaterialUniform.colorAnim.x > 0.5) {
    float oscillator = sin(timeSeconds * g_MaterialUniform.colorAnim.y);
    signal = sin(signal) * sign(oscillator) * pow(abs(oscillator), g_MaterialUniform.colorAnim.z);
  } else {
    signal = sin(signal);
  }
  signal = signal * 0.5 + 0.5;
  float vignetteWidth = g_MaterialUniform.effects1.y - g_MaterialUniform.effects1.x;
  if (g_MaterialUniform.effects1.y > 0.0 && vignetteWidth > 0.0001) {
    signal = mix(
        signal,
        g_MaterialUniform.effects1.z,
        smoothstep(g_MaterialUniform.effects1.x, g_MaterialUniform.effects1.y, length(rawUv)));
  }
  return signal;
}

// Depth follows palette roles rather than RGB luminance. For two colors the
// first slot is far and the second is near. For three colors the first is far,
// the second stays on the carrier, and the third is near. This preserves the
// interference structure even when palette colors have similar brightness.
float depthInterferencePaletteCoordinate(vec2 rawUv, float timeSeconds) {
  float signal = depthInterferenceSignal(rawUv, timeSeconds);
  if (g_MaterialUniform.modeTime.w < 0.0) signal = 1.0 - signal;
  return clamp(signal, 0.0, 1.0);
}

void main() {
  App2VertexUnpacked app = getApp2VertexUnpacked();
  vec3 carrierPosition = app.position;
  vec3 viewerFacingNormal = vec3(0.0, 0.0, 1.0);
  float polarAngle =
      g_MaterialUniform.carrierShape.z * clamp(g_MaterialUniform.carrierShape.y, 0.0, 1.0);
  float carrierRadius = max(g_MaterialUniform.carrierShape.w, 0.0001);
  float flatRadius = length(app.position.xy);
  if (
      g_MaterialUniform.carrierShape.x > 0.5 &&
      polarAngle > 0.0001 &&
      flatRadius > 0.0001) {
    float normalizedRadius = clamp(flatRadius / carrierRadius, 0.0, 1.0);
    float sphereRadius = carrierRadius / sin(polarAngle);
    float pointAngle = normalizedRadius * polarAngle;
    float mappedRadius = sphereRadius * sin(pointAngle);
    carrierPosition.xy = app.position.xy * (mappedRadius / flatRadius);
    // Positive local Z puts the rim toward the viewer on this carrier pose,
    // exposing the inside of the spherical bowl rather than its convex shell.
    carrierPosition.z += sphereRadius * (1.0 - cos(pointAngle));
    viewerFacingNormal =
        normalize(vec3(-carrierPosition.xy, sphereRadius - carrierPosition.z));
  }
  if (
      g_MaterialUniform.depthDeformation.x > 0.5 &&
      g_MaterialUniform.depthDeformation.y > 0.0 &&
      g_MaterialUniform.modeTime.x > 0.5 &&
      g_MaterialUniform.modeTime.x < 1.5) {
    float paletteCoordinate =
        depthInterferencePaletteCoordinate(
            app.position.xy / max(carrierRadius, 0.0001),
            g_MaterialUniform.modeTime.y);
    float signedDisplacement =
        g_MaterialUniform.depthDeformation.y * (paletteCoordinate * 2.0 - 1.0);
    carrierPosition += viewerFacingNormal * signedDisplacement;
  }
  vec4 worldPosition = g_PrimitiveUniform.worldFromObject * vec4(carrierPosition, 1.0);
  vertexOut.objectPosition = app.position;
  gl_Position = getClipFromWorld() * worldPosition;
  postprocessPosition(gl_Position);
}

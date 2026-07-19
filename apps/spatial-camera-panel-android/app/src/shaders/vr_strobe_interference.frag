#version 430
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable

#include <data/shaders/common.glsl>

// Spatial SDK port derived with permission from Trevor Hewitt's vr_strobe,
// pinned at 52c71cc069f4102bc4148e05c5fd3fc4d5466479. Port: AGPL-3.0-or-later.

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

layout(location = 0) in struct {
  vec3 objectPosition;
} vertexOut;

layout(location = 0) out vec4 outColor;

const float TAU = 6.28318530718;
const float CARRIER_RADIUS = 2.84;
const int MAX_INTERFERENCE_SIGNAL_EVALUATIONS = 1;

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

LOAD_FUNCTION(loadStripe, stripe)
LOAD_FUNCTION(loadRipple, ripple)
LOAD_FUNCTION(loadRay, ray)
LOAD_FUNCTION(loadPerlin, perlin)

float hash31(vec3 point) {
  point = fract(point * 0.3183099 + 0.1);
  point *= 17.0;
  return fract(point.x * point.y * point.z * (point.x + point.y + point.z));
}

float noise3D(vec3 point) {
  vec3 cell = floor(point);
  vec3 fraction = fract(point);
  fraction = fraction * fraction * (3.0 - 2.0 * fraction);
  return mix(
      mix(
          mix(hash31(cell + vec3(0, 0, 0)), hash31(cell + vec3(1, 0, 0)), fraction.x),
          mix(hash31(cell + vec3(0, 1, 0)), hash31(cell + vec3(1, 1, 0)), fraction.x),
          fraction.y),
      mix(
          mix(hash31(cell + vec3(0, 0, 1)), hash31(cell + vec3(1, 0, 1)), fraction.x),
          mix(hash31(cell + vec3(0, 1, 1)), hash31(cell + vec3(1, 1, 1)), fraction.x),
          fraction.y),
      fraction.z);
}

float interleavedGradientNoise(vec2 pixel, float timeSeconds, float frequency) {
  vec2 coordinate =
      floor(pixel) +
      floor(timeSeconds * max(frequency, 0.1)) * vec2(47.0, 113.0);
  return fract(
      52.9829189 *
      fract(dot(coordinate, vec2(0.06711056, 0.00583715))));
}

vec2 rotatePoint(vec2 point, float angle, vec2 pivot) {
  float sine = sin(angle);
  float cosine = cos(angle);
  point -= pivot;
  return vec2(point.x * cosine - point.y * sine, point.x * sine + point.y * cosine) + pivot;
}

vec2 movingOffset(float timeSeconds, float seed, float amount) {
  return vec2(
      sin(timeSeconds * 0.5 + seed) * cos(timeSeconds * 0.3 + seed * 2.0),
      cos(timeSeconds * 0.4 + seed * 3.0) * sin(timeSeconds * 0.6 + seed * 1.5)) * amount;
}

float shapedSine(float value) {
  float sine = sin(value);
  float edgeWidth = max(fwidth(sine), 0.001);
  float square = smoothstep(-edgeWidth, edgeWidth, sine) * 2.0 - 1.0;
  float phaseFootprint = max(fwidth(value), 0.001);
  float bandLimit = min(1.0, TAU / phaseFootprint);
  return mix(sine, square, g_MaterialUniform.global2.x) * bandLimit;
}

float interferenceSignal(vec2 rawUv, float timeSeconds) {
  vec2 shake = vec2(
      sin(timeSeconds * g_MaterialUniform.global1.w),
      cos(timeSeconds * g_MaterialUniform.global1.w * 1.3)) * g_MaterialUniform.global1.z;
  vec2 uv = rawUv + g_MaterialUniform.global1.xy + shake;
  uv.x += uv.y * g_MaterialUniform.global0.y;
  uv.y += uv.x * g_MaterialUniform.global0.z;
  uv *= g_MaterialUniform.global0.x;
  uv = rotatePoint(uv, timeSeconds * g_MaterialUniform.global0.w, vec2(0.0));
  float signal = 0.0;

  for (int index = 0; index < 8; ++index) {
    if (index >= int(g_MaterialUniform.patternCounts.x + 0.5)) break;
    vec4 a; vec4 b; vec4 c; vec4 d; vec4 e;
    loadStripe(index, a, b, c, d, e);
    if (a.x < 0.5) continue;
    vec2 point = rotatePoint(uv, -(d.z + timeSeconds * d.w), b.xy) - b.xy;
    if (b.w > 0.0) {
      float distortion = noise3D(vec3(
          point.x * b.z * c.y,
          point.y * b.z * c.z,
          timeSeconds * c.x)) * 2.0 - 1.0;
      point += distortion * b.w;
    }
    if (d.x > 0.0) {
      float wavePosition = point.y * c.w;
      point.x += mix(sin(wavePosition), asin(sin(wavePosition)) * 0.636619, d.y) * d.x;
    }
    float value = shapedSine(point.x * a.z - timeSeconds * a.w);
    float fade = 1.0;
    if (e.x > 0.0) {
      float distanceInCycles = abs(point.x * a.z) / TAU;
      fade = 1.0 - smoothstep(e.x * 0.5, e.x, distanceInCycles);
    }
    signal += value * fade * a.y;
  }

  for (int index = 0; index < 8; ++index) {
    if (index >= int(g_MaterialUniform.patternCounts.y + 0.5)) break;
    vec4 a; vec4 b; vec4 c; vec4 d; vec4 e;
    loadRipple(index, a, b, c, d, e);
    if (a.x < 0.5) continue;
    vec2 pivot = b.xy + movingOffset(timeSeconds, float(index) * 10.0, e.w);
    vec2 point = rotatePoint(uv, -timeSeconds * d.w, e.yz);
    vec2 delta = point - pivot;
    float radius = length(delta);
    float angle = atan(delta.y, delta.x);
    if (b.w > 0.0) {
      float distortion = noise3D(vec3(
          radius * b.z * c.y,
          angle * b.z * c.z,
          timeSeconds * c.x)) * 2.0 - 1.0;
      radius += distortion * b.w;
    }
    if (d.x > 0.0) {
      float wavePosition = angle * c.w;
      radius += mix(sin(wavePosition), asin(sin(wavePosition)) * 0.636619, d.y) * d.x;
    }
    signal += shapedSine(radius * a.z - timeSeconds * a.w) * a.y;
  }

  for (int index = 0; index < 8; ++index) {
    if (index >= int(g_MaterialUniform.patternCounts.z + 0.5)) break;
    vec4 a; vec4 b; vec4 c; vec4 d; vec4 e;
    loadRay(index, a, b, c, d, e);
    if (a.x < 0.5) continue;
    vec2 pivot = b.xy + movingOffset(timeSeconds, float(index) * 20.0, e.w);
    vec2 point = rotatePoint(uv, -timeSeconds * d.w, e.yz);
    vec2 delta = point - pivot;
    float radius = length(delta);
    float angle = atan(delta.y, delta.x);
    if (b.w > 0.0) {
      float distortion = noise3D(vec3(
          angle * b.z * c.y,
          radius * b.z * c.z,
          timeSeconds * c.x)) * 2.0 - 1.0;
      angle += distortion * b.w;
    }
    if (d.x > 0.0) {
      float wavePosition = radius * c.w;
      angle += mix(sin(wavePosition), asin(sin(wavePosition)) * 0.636619, d.y) * d.x;
    }
    signal += shapedSine(angle * floor(a.z) - timeSeconds * a.w) * a.y;
  }

  for (int index = 0; index < 8; ++index) {
    if (index >= int(g_MaterialUniform.patternCounts.w + 0.5)) break;
    vec4 a; vec4 b; vec4 c; vec4 d; vec4 e;
    loadPerlin(index, a, b, c, d, e);
    if (a.x < 0.5) continue;
    vec2 point = uv - b.xy;
    float value = noise3D(vec3(point * a.z, b.z + timeSeconds * a.w));
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

vec3 interferencePalette(float signal) {
  return
      g_MaterialUniform.colorAnim.w < 2.5
          ? mix(g_MaterialUniform.color1.rgb, g_MaterialUniform.color2.rgb, signal)
          : (signal < 0.5
                 ? mix(g_MaterialUniform.color1.rgb, g_MaterialUniform.color2.rgb, signal * 2.0)
                 : mix(g_MaterialUniform.color2.rgb, g_MaterialUniform.color3.rgb, (signal - 0.5) * 2.0));
}

vec3 interferenceColor(vec2 uv, float timeSeconds) {
  float signal = interferenceSignal(uv, timeSeconds);
  vec3 color = interferencePalette(signal);
  float trail = g_MaterialUniform.post0.x;
  float blurRadius = g_MaterialUniform.post0.y;
  float blurMix = clamp(blurRadius / 15.0, 0.0, 1.0);
  // Spatial SDK has no persistent feedback target in this one-draw material.
  // A bounded palette-domain softening preserves the visual role of trail and
  // blur without reevaluating every procedural pattern for neighboring samples.
  float softening = clamp(trail * 0.14 + blurMix * 0.22, 0.0, 0.30);
  float softenedSignal = mix(signal, smoothstep(0.08, 0.92, signal), 0.35);
  vec3 softened = interferencePalette(softenedSignal);
  color = mix(color, softened, softening);
  color += max(color - 0.5, 0.0) * g_MaterialUniform.post0.z;
  color = (color - 0.5) * g_MaterialUniform.effects0.x + 0.5 + g_MaterialUniform.post0.w;
  if (g_MaterialUniform.effects0.z > 0.0) {
    float noise =
        interleavedGradientNoise(
            gl_FragCoord.xy,
            timeSeconds,
            g_MaterialUniform.effects0.y);
    color = mix(
        color,
        mix(color, vec3(g_MaterialUniform.effects0.w), noise),
        g_MaterialUniform.effects0.z);
  }
  return clamp(color, 0.0, 1.0);
}

vec3 temporalColor(vec2 uv, float timeSeconds) {
  float phase = fract(timeSeconds * g_MaterialUniform.strobe0.x);
  bool firstPhase = phase < g_MaterialUniform.strobe0.y;
  vec3 color = firstPhase ? g_MaterialUniform.color1.rgb : g_MaterialUniform.color2.rgb;
  float useNoise = firstPhase ? g_MaterialUniform.strobe1.x : g_MaterialUniform.strobe1.z;
  float amplitude = firstPhase ? g_MaterialUniform.strobe1.y : g_MaterialUniform.strobe1.w;
  if (useNoise > 0.5 && amplitude > 0.0) {
    float resolution = max(1.0, g_MaterialUniform.strobe0.w);
    vec2 noiseCell = floor((uv + 1.0) * 256.0 / resolution);
    float noise =
        g_MaterialUniform.strobe0.z > 0.5
            ? noise3D(vec3(noiseCell * 0.05, 0.0))
            : hash31(vec3(noiseCell, 19.7));
    color += vec3(noise * 2.0 - 1.0) * amplitude;
  }
  if (g_MaterialUniform.strobe2.x > 0.5) {
    float halfSize = max(0.004, g_MaterialUniform.strobe2.y / 700.0);
    float thickness = max(0.0015, halfSize * 0.16);
    float cross = max(
        step(abs(uv.x), thickness) * step(abs(uv.y), halfSize),
        step(abs(uv.y), thickness) * step(abs(uv.x), halfSize));
    color = mix(color, g_MaterialUniform.fixationColor.rgb, cross);
  }
  return clamp(color, 0.0, 1.0);
}

void main() {
  float mode = g_MaterialUniform.modeTime.x;
  if (mode < 0.5) {
    outColor = vec4(0.0, 0.0, 0.0, 1.0);
    return;
  }
  vec2 uv = vertexOut.objectPosition.xy / CARRIER_RADIUS;
  float timeSeconds = g_MaterialUniform.modeTime.y;
  vec3 color = mode < 1.5 ? interferenceColor(uv, timeSeconds) : temporalColor(uv, timeSeconds);
  outColor = vec4(color, 1.0);
}

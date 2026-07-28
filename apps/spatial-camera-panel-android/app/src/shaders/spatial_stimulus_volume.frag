#version 430
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable

#include <data/shaders/common.glsl>

layout(std140, set = 3, binding = 0) uniform MaterialUniform {
  vec4 profileParams;
  vec4 sourceA;
  vec4 sourceB;
  vec4 colorNear;
  vec4 colorMid;
  vec4 colorFar;
  vec4 fixedParams;
} g_MaterialUniform;

layout(location = 0) in struct {
  vec3 objectPosition;
} vertexOut;

layout(location = 0) out vec4 outColor;

const float TAU = 6.28318530718;
const int SAMPLE_COUNT = 16;
const vec2 CARRIER_HALF_EXTENT = vec2(1.25, 1.05);

float hash31(vec3 point) {
  point = fract(point * 0.1031);
  point += dot(point, point.yzx + 33.33);
  return fract((point.x + point.y) * point.z);
}

float valueNoise(vec3 point) {
  vec3 cell = floor(point);
  vec3 fraction = fract(point);
  fraction = fraction * fraction * (3.0 - 2.0 * fraction);

  float n000 = hash31(cell + vec3(0.0, 0.0, 0.0));
  float n100 = hash31(cell + vec3(1.0, 0.0, 0.0));
  float n010 = hash31(cell + vec3(0.0, 1.0, 0.0));
  float n110 = hash31(cell + vec3(1.0, 1.0, 0.0));
  float n001 = hash31(cell + vec3(0.0, 0.0, 1.0));
  float n101 = hash31(cell + vec3(1.0, 0.0, 1.0));
  float n011 = hash31(cell + vec3(0.0, 1.0, 1.0));
  float n111 = hash31(cell + vec3(1.0, 1.0, 1.0));

  float nx00 = mix(n000, n100, fraction.x);
  float nx10 = mix(n010, n110, fraction.x);
  float nx01 = mix(n001, n101, fraction.x);
  float nx11 = mix(n011, n111, fraction.x);
  float nxy0 = mix(nx00, nx10, fraction.y);
  float nxy1 = mix(nx01, nx11, fraction.y);
  return mix(nxy0, nxy1, fraction.z);
}

float fbm2(vec3 point) {
  return valueNoise(point) * 0.66 + valueNoise(point * 2.07 + 13.1) * 0.34;
}

float wave01(float value) {
  return 0.5 + 0.5 * sin(value);
}

vec3 depthRamp(float depth01) {
  vec3 nearToMid = mix(
      g_MaterialUniform.colorNear.rgb,
      g_MaterialUniform.colorMid.rgb,
      smoothstep(0.0, 0.55, depth01));
  vec3 midToFar = mix(
      g_MaterialUniform.colorMid.rgb,
      g_MaterialUniform.colorFar.rgb,
      smoothstep(0.42, 1.0, depth01));
  return mix(nearToMid, midToFar, smoothstep(0.35, 0.9, depth01));
}

vec3 applyFixedWarp(vec3 point, float slice01, float noiseScale) {
  const float pinch = 0.08;
  const float domainWarpStrength = 0.04;
  float radius = max(length(point.xy), 0.0001);
  float angle = atan(point.y, point.x);
  float pinchedRadius =
      radius * pow(clamp(radius * 1.35 + 0.18, 0.05, 3.2), -pinch * 0.55);
  point.xy = vec2(cos(angle), sin(angle)) * pinchedRadius;

  float domainNoise =
      fbm2(point * (noiseScale * 0.72 + 1.7) + vec3(0.0, 0.0, slice01 * 5.0));
  point.xy += (domainNoise - 0.5) * domainWarpStrength * vec2(0.32, -0.24);
  return point;
}

float fixedInterference(
    vec3 point,
    vec3 sourceA,
    vec3 sourceB,
    float spatialScale,
    float noiseValue
) {
  float distanceA = length(point - sourceA);
  float distanceB = length(point - sourceB);
  float radial = wave01(distanceA * 34.0 * spatialScale);
  float axial = wave01((point.z * 15.0 + point.y * 5.0) * 1.06);
  float cross = wave01(((distanceA - distanceB) * 42.0 + point.x * 8.0) * 1.02);
  float interference = radial * axial * cross;
  return mix(interference, interference * (0.70 + 0.64 * noiseValue), 0.46);
}

void main() {
  vec2 screenPoint = vertexOut.objectPosition.xy / CARRIER_HALF_EXTENT;
  vec3 eyeObject =
      (g_PrimitiveUniform.objectFromWorld * vec4(getEyeCenter(), 1.0)).xyz;
  float eyeOffset = clamp(eyeObject.x, -0.05, 0.05);
  vec3 origin = vec3(eyeOffset, -0.015, -0.24);
  vec3 ray = normalize(vec3(
      screenPoint.x * 0.82 + eyeOffset * 0.45,
      screenPoint.y * 0.82,
      1.16));

  float spatialScale = clamp(g_MaterialUniform.profileParams.x, 0.55, 1.90);
  float contrast = g_MaterialUniform.profileParams.y;
  float smoothing = clamp(g_MaterialUniform.profileParams.z, 0.0, 1.0);
  float gain = g_MaterialUniform.profileParams.w;
  float blackThreshold = g_MaterialUniform.sourceB.w;
  float sourceBWeight = g_MaterialUniform.sourceA.w;
  float fixedPhase = g_MaterialUniform.fixedParams.x;
  vec3 fixedPhaseOffsets = vec3(g_MaterialUniform.fixedParams.y);
  const float noiseScale = 4.5;
  const float depthWarp = 0.04;
  float gateWidth = mix(0.16, 0.34, smoothing);
  vec3 integrated = vec3(0.0);
  vec3 peakColor = vec3(0.0);
  float peak = 0.0;
  float densitySum = 0.0;

  for (int index = 0; index < SAMPLE_COUNT; ++index) {
    float slice01 = (float(index) + 0.5) / float(SAMPLE_COUNT);
    float distanceAlongRay = mix(0.18, 1.38, slice01);
    vec3 point = origin + ray * distanceAlongRay;
    point.xy += (0.035 + depthWarp * 0.55) * vec2(
        sin(fixedPhase + slice01 * TAU + fixedPhaseOffsets.x),
        cos(fixedPhase + slice01 * 5.4977871 + fixedPhaseOffsets.y));
    point.z += depthWarp *
        sin(fixedPhase + slice01 * 7.8539816 + fixedPhaseOffsets.z);
    vec3 patternPoint = applyFixedWarp(point, slice01, noiseScale);
    float noiseValue = fbm2(patternPoint * noiseScale);
    float interference = fixedInterference(
        patternPoint,
        g_MaterialUniform.sourceA.xyz,
        g_MaterialUniform.sourceB.xyz,
        spatialScale,
        noiseValue);

    float gate = smoothstep(
        max(0.0, blackThreshold - 0.035 * smoothing),
        min(0.99, blackThreshold + gateWidth),
        interference);
    gate *= smoothstep(0.04, 0.28, slice01) *
        (1.0 - smoothstep(0.68, 1.0, slice01));
    vec3 sampleColor = depthRamp(slice01) * gate * gain * sourceBWeight;
    integrated += sampleColor * (0.12 + 0.88 * gate);
    peakColor = max(peakColor, sampleColor);
    peak = max(peak, gate);
    densitySum += gate;
  }

  float normalization = max(1.0, float(SAMPLE_COUNT) * mix(0.32, 0.46, smoothing));
  vec3 accumulated = mix(peakColor, integrated / normalization, smoothing);
  accumulated *= mix(
      1.0,
      1.0 + min(1.25, densitySum / float(SAMPLE_COUNT)),
      smoothing);
  vec3 color = pow(
      clamp(accumulated, 0.0, 1.0),
      vec3(max(0.35, 1.0 - contrast * 0.35)));
  color *= smoothstep(0.02, 0.20, peak);
  outColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}

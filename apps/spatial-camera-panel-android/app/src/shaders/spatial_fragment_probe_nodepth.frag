#version 430
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable

#include <data/shaders/common.glsl>

layout(std140, set = 3, binding = 0) uniform MaterialUniform {
  vec4 stereoParams;
  vec4 probeParams;
  vec4 colorParams;
} g_MaterialUniform;

layout(location = 0) in struct {
  vec3 objectPosition;
  vec3 worldPosition;
  vec3 worldNormal;
  vec2 uv;
} vertexOut;

layout(location = 0) out vec4 outColor;

const int RAYMARCH_STEPS = 12;
const vec3 PROXY_HALF_EXTENT = vec3(0.35, 0.28, 0.35);

float sdfSphere(vec3 point, float radius) {
  return length(point) - radius;
}

float sdfBox(vec3 point, vec3 halfExtent) {
  vec3 delta = abs(point) - halfExtent;
  return length(max(delta, vec3(0.0))) + min(max(delta.x, max(delta.y, delta.z)), 0.0);
}

float sceneDistance(vec3 point) {
  float sphere = sdfSphere(point + vec3(0.08, -0.01, 0.03), 0.19);
  float box = sdfBox(point - vec3(0.12, 0.06, -0.04), vec3(0.12, 0.11, 0.12));
  return min(sphere, box);
}

vec2 intersectProxy(vec3 origin, vec3 direction) {
  vec3 inverseDirection = 1.0 / direction;
  vec3 nearPlane = (-PROXY_HALF_EXTENT - origin) * inverseDirection;
  vec3 farPlane = (PROXY_HALF_EXTENT - origin) * inverseDirection;
  vec3 smaller = min(nearPlane, farPlane);
  vec3 larger = max(nearPlane, farPlane);
  float nearDistance = max(max(smaller.x, smaller.y), smaller.z);
  float farDistance = min(min(larger.x, larger.y), larger.z);
  return vec2(nearDistance, farDistance);
}

vec3 surfaceNormal(vec3 point) {
  const float epsilon = 0.002;
  vec2 offset = vec2(epsilon, 0.0);
  return normalize(vec3(
      sceneDistance(point + offset.xyy) - sceneDistance(point - offset.xyy),
      sceneDistance(point + offset.yxy) - sceneDistance(point - offset.yxy),
      sceneDistance(point + offset.yyx) - sceneDistance(point - offset.yyx)));
}

vec3 flatPattern() {
  vec2 coordinates = vertexOut.objectPosition.xy / vec2(0.70, 0.56) + 0.5;
  float checker = mod(floor(coordinates.x * 10.0) + floor(coordinates.y * 8.0), 2.0);
  float ring = smoothstep(0.035, 0.0, abs(length(coordinates - 0.5) - 0.27));
  vec3 darkColor = vec3(0.035, 0.06, 0.11);
  vec3 brightColor = g_MaterialUniform.colorParams.rgb;
  return mix(darkColor, brightColor, 0.22 + checker * 0.58) + ring * vec3(1.0, 0.18, 0.72);
}

bool raymarch(out vec3 hitPoint, out vec3 hitNormal) {
  vec3 eyeObject =
      (g_PrimitiveUniform.objectFromWorld * vec4(getEyeCenter(), 1.0)).xyz;
  vec3 direction = normalize(vertexOut.objectPosition - eyeObject);
  vec2 interval = intersectProxy(eyeObject, direction);
  float distanceAlongRay = max(interval.x, 0.0);
  bool hit = false;
  for (int step = 0; step < RAYMARCH_STEPS; ++step) {
    vec3 point = eyeObject + direction * distanceAlongRay;
    float distanceToSurface = sceneDistance(point);
    if (distanceToSurface < 0.004) {
      hitPoint = point;
      hit = true;
      break;
    }
    distanceAlongRay += max(distanceToSurface, 0.006);
    if (distanceAlongRay > interval.y) {
      break;
    }
  }
  if (hit) {
    hitNormal = surfaceNormal(hitPoint);
  }
  return hit;
}

void main() {
  if (g_MaterialUniform.probeParams.x < 0.5) {
    outColor = vec4(flatPattern(), 1.0);
    return;
  }

  vec3 hitPoint = vec3(0.0);
  vec3 hitNormal = vec3(0.0, 0.0, 1.0);
  if (!raymarch(hitPoint, hitNormal)) {
    discard;
  }
  vec3 lightDirection = normalize(vec3(-0.35, 0.75, 0.55));
  float diffuse = 0.25 + 0.75 * max(dot(hitNormal, lightDirection), 0.0);
  float contour = 0.5 + 0.5 * sin((hitPoint.x + hitPoint.y - hitPoint.z) * 34.0);
  vec3 baseColor = mix(g_MaterialUniform.colorParams.rgb, vec3(1.0, 0.12, 0.68), contour * 0.32);
  outColor = vec4(baseColor * diffuse, 1.0);
}

#version 450

layout(set = 0, binding = 2, std430) readonly buffer PrivateParticleRows {
  vec4 rows[];
} particleOutput;

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

layout(location = 0) out vec2 outMaskUv;
layout(location = 1) out vec4 outColor;
layout(location = 2) out vec4 outParticleParams;

const vec2 QUAD_POSITIONS[6] = vec2[](
    vec2(-1.0, -1.0),
    vec2( 1.0, -1.0),
    vec2(-1.0,  1.0),
    vec2(-1.0,  1.0),
    vec2( 1.0, -1.0),
    vec2( 1.0,  1.0)
);

vec3 safe_normalize(vec3 value, vec3 fallback) {
  float lenSq = dot(value, value);
  return lenSq > 0.00000001 ? value * inversesqrt(lenSq) : fallback;
}

vec2 world_to_panel(
    vec3 world,
    vec3 eyePosition,
    vec3 panelCenter,
    vec3 panelRight,
    vec3 panelUp,
    vec3 panelForward,
    out bool valid,
    out float depth,
    out float planeDistance) {
  vec3 ray = world - eyePosition;
  depth = dot(ray, panelForward);
  planeDistance = dot(panelCenter - eyePosition, panelForward);
  valid = depth > 0.030 && planeDistance > 0.030;
  float t = planeDistance / max(depth, 0.030);
  vec3 hit = eyePosition + ray * t;
  vec3 rel = hit - panelCenter;
  float halfWidth = max(pc.panelRightWidth.w * 0.5, 0.001);
  float halfHeight = max(pc.panelUpHeight.w * 0.5, 0.001);
  return vec2(dot(rel, panelRight) / halfWidth, dot(rel, panelUp) / halfHeight);
}

void main() {
  uint drawCount = max(uint(pc.tracerParams.x), 1u);
  uint drawIndex = min(uint(gl_InstanceIndex), drawCount - 1u);
  uint base = drawIndex * 4u;
  vec4 positionRadius = particleOutput.rows[base];
  vec4 colorAlpha = particleOutput.rows[base + 1u];
  vec4 normalFlags = particleOutput.rows[base + 2u];
  vec4 aux = particleOutput.rows[base + 3u];
  vec2 rawQuad = QUAD_POSITIONS[uint(gl_VertexIndex) % 6u];
  float rotation = aux.x;
  float cs = cos(rotation);
  float sn = sin(rotation);
  vec2 quad = vec2(
      rawQuad.x * cs - rawQuad.y * sn,
      rawQuad.x * sn + rawQuad.y * cs);

  vec3 panelCenter = pc.worldCenterScale.xyz;
  vec3 panelRight = safe_normalize(pc.panelRightWidth.xyz, vec3(1.0, 0.0, 0.0));
  vec3 panelUp = safe_normalize(pc.panelUpHeight.xyz, vec3(0.0, 1.0, 0.0));
  vec3 panelForward = safe_normalize(pc.forwardDistance.xyz, safe_normalize(cross(panelUp, panelRight), vec3(0.0, 0.0, -1.0)));
  vec3 eyePosition = pc.params1.xyz;

  bool projectionValid = false;
  float depth = 0.0;
  float planeDistance = 1.0;
  vec2 centerPanel = world_to_panel(
      positionRadius.xyz,
      eyePosition,
      panelCenter,
      panelRight,
      panelUp,
      panelForward,
      projectionValid,
      depth,
      planeDistance);
  bool centerInsidePanel = abs(centerPanel.x) < 1.24 && abs(centerPanel.y) < 1.24;
  float radiusM = max(positionRadius.w, 0.0005);
  float projectedRadius = radiusM * (planeDistance / max(depth, 0.030));
  vec2 radiusPanel = vec2(
      projectedRadius / max(pc.panelRightWidth.w * 0.5, 0.001),
      projectedRadius / max(pc.panelUpHeight.w * 0.5, 0.001));
  vec2 panelNdc = centerPanel + quad * radiusPanel;

  bool valid = normalFlags.w > 0.5
      && colorAlpha.a > 0.002
      && projectionValid
      && centerInsidePanel;

  gl_Position = valid ? vec4(panelNdc.x, -panelNdc.y, 0.0, 1.0) : vec4(4.0, 4.0, 0.0, 1.0);
  outMaskUv = rawQuad * 0.5 + vec2(0.5);
  outColor = valid ? colorAlpha : vec4(0.0);
  outParticleParams = vec4(1.0, depth, aux.y, valid ? 1.0 : 0.0);
}

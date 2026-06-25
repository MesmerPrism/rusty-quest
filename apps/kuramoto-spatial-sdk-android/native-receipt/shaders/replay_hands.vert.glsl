#version 450

struct ReplayHandVertex {
  vec4 position;
  vec4 normalHand;
};

struct LiveHandJoint {
  vec4 positionRadius;
  vec4 status;
  vec4 orientationXyzw;
};

struct SkinningVertex {
  vec4 bindPosition;
  vec4 bindNormal;
  uvec4 jointIndices;
  vec4 jointWeights;
};

struct GpuPose {
  vec4 translationPad;
  vec4 rotationXyzw;
};

struct SurfaceParticle {
  vec3 center;
  vec3 normal;
  vec3 tangent;
  vec3 bitangent;
  vec3 driver;
  float radius;
  float anchorPhase;
  float debug0;
  float debug1;
  bool valid;
};

layout(set = 0, binding = 0, std430) readonly buffer ReplayHandVertexBuffer {
  ReplayHandVertex vertices[];
} replayHands;

layout(set = 0, binding = 1, std430) readonly buffer LiveHandJointBuffer {
  LiveHandJoint joints[];
} liveHands;

layout(set = 0, binding = 2, std430) readonly buffer SkinningVertexBuffer {
  SkinningVertex vertices[];
} skinningVertices;

layout(set = 0, binding = 3, std430) readonly buffer SkinningTriangleBuffer {
  uvec4 triangles[];
} skinningTriangles;

layout(set = 0, binding = 4, std430) readonly buffer BindJointPoseBuffer {
  GpuPose poses[];
} bindJointPoses;

layout(set = 0, binding = 5, std430) readonly buffer BindJointSourceBuffer {
  uvec4 rows[];
} bindJointSources;

layout(push_constant) uniform ReplayHandPush {
  uint eyeIndex;
  uint handIndex;
  uint frameIndex;
  uint _pad0;
  uvec4 draw;
  vec4 projection;
  vec4 color;
  vec4 dynamics;
  vec4 profile;
  vec4 panelUpHeight;
  vec4 liveAdjust;
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

const float TAU = 6.28318530718;
const float REPLAY_HAND_IPD_METERS = 0.064;
const float NATURAL_FREQUENCY_NOISE_FREQUENCY = 18.0;
const float NOISE_TEMPORAL_DOMAIN_SCALE = 4.0;
const float KURAMOTO_HIGH_ENERGY_UNIT_DISTANCE_M = 0.004;
const float KURAMOTO_LOW_ENERGY_UNIT_DISTANCE_M = 0.0005;
const uint OPENXR_HAND_JOINT_COUNT = 26u;
const uint BIND_JOINT_COUNT_PER_HAND = 26u;
const uint LIVE_MESH_TRIANGLE_VALIDATION_ATTEMPTS = 6u;

float hash01(uint seed) {
  seed ^= seed >> 16u;
  seed *= 2246822519u;
  seed ^= seed >> 13u;
  seed *= 3266489917u;
  seed ^= seed >> 16u;
  return float(seed & 0x00ffffffu) / float(0x01000000u);
}

vec3 safe_normalize(vec3 value, vec3 fallback) {
  float lenSq = dot(value, value);
  return lenSq > 0.00000001 ? value * inversesqrt(lenSq) : fallback;
}

vec4 safe_normalize_quat(vec4 quat) {
  float lenSq = max(dot(quat, quat), 0.000000000001);
  return quat * inversesqrt(lenSq);
}

vec4 inverse_quat(vec4 quat) {
  vec4 q = safe_normalize_quat(quat);
  return vec4(-q.xyz, q.w);
}

vec3 rotate_by_quat(vec4 quat, vec3 vector) {
  vec4 q = safe_normalize_quat(quat);
  vec3 uv = cross(q.xyz, vector);
  vec3 uuv = cross(q.xyz, uv);
  return vector + uv * (2.0 * q.w) + uuv * 2.0;
}

vec3 transform_point(GpuPose pose, vec3 point) {
  return rotate_by_quat(pose.rotationXyzw, point) + pose.translationPad.xyz;
}

vec3 inverse_transform_point(GpuPose pose, vec3 point) {
  return rotate_by_quat(inverse_quat(pose.rotationXyzw), point - pose.translationPad.xyz);
}

float browser_fade(float value) {
  return value * value * value * (value * (value * 6.0 - 15.0) + 10.0);
}

float browser_lerp(float left, float right, float t) {
  return left + (right - left) * t;
}

float browser_grad(uint hash, float x, float y, float z) {
  uint h = hash & 0x0fu;
  if (h == 0u) { return x + y; }
  if (h == 1u) { return -x + y; }
  if (h == 2u) { return x - y; }
  if (h == 3u) { return -x - y; }
  if (h == 4u) { return x + z; }
  if (h == 5u) { return -x + z; }
  if (h == 6u) { return x - z; }
  if (h == 7u) { return -x - z; }
  if (h == 8u) { return y + z; }
  if (h == 9u) { return -y + z; }
  if (h == 10u) { return y - z; }
  if (h == 11u) { return -y - z; }
  if (h == 12u) { return x + y; }
  if (h == 13u) { return -x + y; }
  if (h == 14u) { return y - z; }
  return -y - z;
}

uint browser_hash3(uint seed, int x, int y, int z) {
  uint value = seed
      ^ uint(x) * 0x8da6b343u
      ^ uint(y) * 0xd8163841u
      ^ uint(z) * 0xcb1ab31fu;
  value = (value ^ (value >> 16u)) * 0x7feb352du;
  value = (value ^ (value >> 15u)) * 0x846ca68bu;
  return value ^ (value >> 16u);
}

float browser_perlin3(uint seed, vec3 p) {
  vec3 floorP = floor(p);
  ivec3 cell = ivec3(floorP);
  vec3 f = p - floorP;
  float u = browser_fade(f.x);
  float v = browser_fade(f.y);
  float w = browser_fade(f.z);
  float x00 = browser_lerp(
      browser_grad(browser_hash3(seed, cell.x, cell.y, cell.z), f.x, f.y, f.z),
      browser_grad(browser_hash3(seed, cell.x + 1, cell.y, cell.z), f.x - 1.0, f.y, f.z),
      u);
  float x10 = browser_lerp(
      browser_grad(browser_hash3(seed, cell.x, cell.y + 1, cell.z), f.x, f.y - 1.0, f.z),
      browser_grad(browser_hash3(seed, cell.x + 1, cell.y + 1, cell.z), f.x - 1.0, f.y - 1.0, f.z),
      u);
  float x01 = browser_lerp(
      browser_grad(browser_hash3(seed, cell.x, cell.y, cell.z + 1), f.x, f.y, f.z - 1.0),
      browser_grad(browser_hash3(seed, cell.x + 1, cell.y, cell.z + 1), f.x - 1.0, f.y, f.z - 1.0),
      u);
  float x11 = browser_lerp(
      browser_grad(browser_hash3(seed, cell.x, cell.y + 1, cell.z + 1), f.x, f.y - 1.0, f.z - 1.0),
      browser_grad(browser_hash3(seed, cell.x + 1, cell.y + 1, cell.z + 1), f.x - 1.0, f.y - 1.0, f.z - 1.0),
      u);
  return clamp(browser_lerp(browser_lerp(x00, x10, v), browser_lerp(x01, x11, v), w), -1.0, 1.0);
}

vec3 clamp_vector_length(vec3 value, float maxLength) {
  float len = length(value);
  if (len > maxLength && len > 0.0) {
    return value * (maxLength / len);
  }
  return value;
}

vec3 browser_temporal_offset(float phase) {
  if (phase == 0.0) {
    return vec3(0.0);
  }
  return vec3(
      sin(phase) * 0.47 * NOISE_TEMPORAL_DOMAIN_SCALE,
      sin(phase * 1.37) * 0.31 * NOISE_TEMPORAL_DOMAIN_SCALE,
      sin(phase * 0.73) * 0.59 * NOISE_TEMPORAL_DOMAIN_SCALE);
}

vec3 browser_perlin_displacement_cycles(vec3 position, float frequency, float amplitude, float phaseCycles) {
  if (frequency <= 0.0 || amplitude <= 0.0) {
    return vec3(0.0);
  }
  float phase = max(phaseCycles, 0.0) * TAU;
  vec3 samplePoint = position * frequency + browser_temporal_offset(phase);
  vec3 raw = vec3(
      browser_perlin3(0x234a712fu, samplePoint),
      browser_perlin3(0x9e3779b9u, samplePoint + vec3(19.17, -37.43, 11.29)),
      browser_perlin3(0xd1b54a32u, samplePoint + vec3(-53.71, 7.31, 29.53)));
  return clamp_vector_length(raw, 1.0) * amplitude;
}

vec3 anchor_barycentric(uint anchorIndex) {
  float u = hash01(anchorIndex * 1664525u + 1013904223u);
  float v = hash01(anchorIndex * 22695477u + 1u);
  float su = sqrt(u);
  return vec3(1.0 - su, su * (1.0 - v), su * v);
}

uint openxr_joint_for_runtime(uint runtimeIndex) {
  switch (runtimeIndex) {
    case 0u: return 0u;
    case 1u: return 1u;
    case 2u: return 2u;
    case 3u: return 3u;
    case 4u: return 4u;
    case 5u: return 6u;
    case 6u: return 7u;
    case 7u: return 8u;
    case 8u: return 9u;
    case 9u: return 11u;
    case 10u: return 12u;
    case 11u: return 13u;
    case 12u: return 14u;
    case 13u: return 16u;
    case 14u: return 17u;
    case 15u: return 18u;
    case 16u: return 19u;
    case 17u: return 21u;
    case 18u: return 22u;
    case 19u: return 23u;
    default: return 24u;
  }
}

uvec2 tip_pair(uint index) {
  switch (index) {
    case 0u: return uvec2(4u, 5u);
    case 1u: return uvec2(9u, 10u);
    case 2u: return uvec2(14u, 15u);
    case 3u: return uvec2(19u, 20u);
    default: return uvec2(24u, 25u);
  }
}

LiveHandJoint live_joint_openxr(uint openxrIndex) {
  return liveHands.joints[pc.handIndex * OPENXR_HAND_JOINT_COUNT + openxrIndex];
}

bool compact_runtime_joint_valid(uint runtimeIndex) {
  LiveHandJoint joint = live_joint_openxr(openxr_joint_for_runtime(runtimeIndex));
  return joint.status.y > 0.5;
}

GpuPose live_runtime_pose(uint runtimeIndex) {
  LiveHandJoint joint = live_joint_openxr(openxr_joint_for_runtime(runtimeIndex));
  GpuPose pose;
  pose.translationPad = joint.positionRadius;
  pose.rotationXyzw = joint.orientationXyzw;
  return pose;
}

bool tip_length_valid(uint index) {
  uvec2 pair = tip_pair(index);
  LiveHandJoint distal = live_joint_openxr(pair.x);
  LiveHandJoint tip = live_joint_openxr(pair.y);
  return distal.status.x > 0.5 && tip.status.x > 0.5;
}

float tip_length_at(uint index) {
  uvec2 pair = tip_pair(index);
  LiveHandJoint distal = live_joint_openxr(pair.x);
  LiveHandJoint tip = live_joint_openxr(pair.y);
  if (distal.status.x <= 0.5 || tip.status.x <= 0.5) {
    return 0.0;
  }
  return length(tip.positionRadius.xyz - distal.positionRadius.xyz);
}

uvec4 bind_joint_source(uint bindJointIndex) {
  return bindJointSources.rows[pc.handIndex * BIND_JOINT_COUNT_PER_HAND + bindJointIndex];
}

bool bind_joint_valid(uint bindJointIndex) {
  uvec4 source = bind_joint_source(bindJointIndex);
  if (source.x == 0u) {
    return compact_runtime_joint_valid(source.y);
  }
  return compact_runtime_joint_valid(source.w) && tip_length_valid(source.z);
}

GpuPose current_bind_joint_pose(uint bindJointIndex) {
  uvec4 source = bind_joint_source(bindJointIndex);
  if (source.x == 0u) {
    return live_runtime_pose(source.y);
  }

  GpuPose parent = live_runtime_pose(source.w);
  float lengthM = max(tip_length_at(source.z), 0.0);
  vec3 offset = rotate_by_quat(parent.rotationXyzw, vec3(0.0, 0.0, -lengthM));
  GpuPose result;
  result.translationPad = vec4(parent.translationPad.xyz + offset, 0.0);
  result.rotationXyzw = parent.rotationXyzw;
  return result;
}

vec3 transform_joint(uint bindJointIndex, vec4 p) {
  GpuPose bindPose = bindJointPoses.poses[pc.handIndex * BIND_JOINT_COUNT_PER_HAND + bindJointIndex];
  GpuPose currentPose = current_bind_joint_pose(bindJointIndex);
  return transform_point(currentPose, inverse_transform_point(bindPose, p.xyz));
}

vec3 transform_joint_normal(uint bindJointIndex, vec3 normal) {
  GpuPose bindPose = bindJointPoses.poses[pc.handIndex * BIND_JOINT_COUNT_PER_HAND + bindJointIndex];
  GpuPose currentPose = current_bind_joint_pose(bindJointIndex);
  vec3 localNormal = rotate_by_quat(inverse_quat(bindPose.rotationXyzw), normal);
  return rotate_by_quat(currentPose.rotationXyzw, localNormal);
}

vec3 skin_vertex_at(uint localVertexIndex, out bool valid) {
  SkinningVertex vertex = skinningVertices.vertices[pc.draw.x + localVertexIndex];
  vec4 weights = vertex.jointWeights;
  uvec4 joints = vertex.jointIndices;
  float totalWeight = weights.x + weights.y + weights.z + weights.w;
  if (totalWeight <= 0.0) {
    valid = false;
    return vertex.bindPosition.xyz;
  }

  vec3 weighted =
      transform_joint(joints.x, vertex.bindPosition) * weights.x +
      transform_joint(joints.y, vertex.bindPosition) * weights.y +
      transform_joint(joints.z, vertex.bindPosition) * weights.z +
      transform_joint(joints.w, vertex.bindPosition) * weights.w;
  valid = true;
  return weighted / totalWeight;
}

vec3 skin_normal_at(uint localVertexIndex, out bool valid) {
  SkinningVertex vertex = skinningVertices.vertices[pc.draw.x + localVertexIndex];
  vec4 weights = vertex.jointWeights;
  uvec4 joints = vertex.jointIndices;
  float totalWeight = weights.x + weights.y + weights.z + weights.w;
  if (totalWeight <= 0.0) {
    valid = false;
    return safe_normalize(vertex.bindNormal.xyz, vec3(0.0, 0.0, 1.0));
  }

  vec3 weighted =
      transform_joint_normal(joints.x, vertex.bindNormal.xyz) * weights.x +
      transform_joint_normal(joints.y, vertex.bindNormal.xyz) * weights.y +
      transform_joint_normal(joints.z, vertex.bindNormal.xyz) * weights.z +
      transform_joint_normal(joints.w, vertex.bindNormal.xyz) * weights.w;
  float lenSq = dot(weighted, weighted);
  valid = lenSq > 0.00000001;
  return valid ? weighted * inversesqrt(lenSq) : safe_normalize(vertex.bindNormal.xyz, vec3(0.0, 0.0, 1.0));
}

vec3 initial_phase(uint coordinate) {
  return vec3(
      hash01(coordinate * 747796405u + 2891336453u),
      hash01(coordinate * 277803737u + 1013904223u),
      hash01(coordinate * 1103515245u + 12345u)) * TAU;
}

vec3 lche_driver(uint anchorIndex, vec3 anchor) {
  vec3 frequencyNoise = vec3(
      browser_perlin3(0x4f3a9b21u, anchor * NATURAL_FREQUENCY_NOISE_FREQUENCY),
      browser_perlin3(0xb7c15d83u, anchor * NATURAL_FREQUENCY_NOISE_FREQUENCY),
      browser_perlin3(0x29e46f5du, anchor * NATURAL_FREQUENCY_NOISE_FREQUENCY));
  vec3 hz = max(vec3(0.0), vec3(pc.dynamics.y) + pc.profile.x * frequencyNoise);
  vec3 phase = initial_phase(anchorIndex + pc.handIndex * 4099u) + pc.dynamics.x * hz * TAU;
  float coherence = clamp(pc.dynamics.z, 0.0, 1.0);
  phase += coherence * vec3(
      sin(anchor.x * 11.0 + pc.dynamics.x * 0.7),
      sin(anchor.y * 13.0 + pc.dynamics.x * 0.9),
      sin(anchor.z * 17.0 + pc.dynamics.x * 1.1));
  vec3 driver = clamp(sin(phase), vec3(-1.0), vec3(1.0));
  float driverLen = length(driver);
  return driverLen > 1.0 ? driver / driverLen : driver;
}

void build_basis(vec3 normal, out vec3 tangent, out vec3 bitangent) {
  vec3 helper = abs(dot(normal, vec3(0.0, 1.0, 0.0))) < 0.92
      ? vec3(0.0, 1.0, 0.0)
      : vec3(1.0, 0.0, 0.0);
  tangent = safe_normalize(cross(helper, normal), vec3(1.0, 0.0, 0.0));
  bitangent = safe_normalize(cross(normal, tangent), vec3(0.0, 1.0, 0.0));
}

void apply_lche_dynamics(uint anchorIndex, inout SurfaceParticle particle) {
  particle.driver = lche_driver(anchorIndex, particle.center);
  float highEnergy01 = clamp(pc.profile.y / 0.004, 0.0, 1.0);
  float unitDistance = mix(KURAMOTO_LOW_ENERGY_UNIT_DISTANCE_M, KURAMOTO_HIGH_ENERGY_UNIT_DISTANCE_M, highEnergy01);
  vec3 movement =
      (particle.tangent * particle.driver.x + particle.bitangent * particle.driver.y + particle.normal * particle.driver.z)
      * unitDistance;
  vec3 localNoise = browser_perlin_displacement_cycles(
      particle.driver,
      6.7,
      pc.profile.y,
      pc.dynamics.x * pc.profile.z);
  vec3 worldNoise =
      particle.tangent * localNoise.x + particle.bitangent * localNoise.y + particle.normal * localNoise.z;
  particle.center += movement + worldNoise;
}

SurfaceParticle default_particle(uint anchorIndex) {
  SurfaceParticle particle;
  particle.center = vec3(0.0);
  particle.normal = vec3(0.0, 0.0, 1.0);
  particle.tangent = vec3(1.0, 0.0, 0.0);
  particle.bitangent = vec3(0.0, 1.0, 0.0);
  particle.driver = vec3(0.0);
  particle.radius = pc.projection.w * pc.profile.w;
  particle.anchorPhase = float(anchorIndex) / float(max(pc.draw.w - 1u, 1u));
  particle.debug0 = 0.0;
  particle.debug1 = 0.0;
  particle.valid = false;
  return particle;
}

SurfaceParticle live_mesh_particle(uint anchorIndex, vec3 panelForward) {
  SurfaceParticle particle = default_particle(anchorIndex);
  uint diagnosticMode = uint(pc.liveAdjust.z + 0.5);
  uint triangleCount = max(pc.draw.z, 1u);
  uint baseTriangleIndex = (anchorIndex * 2654435761u + anchorIndex / 3u) % triangleCount;
  for (uint attempt = 0u; attempt < LIVE_MESH_TRIANGLE_VALIDATION_ATTEMPTS; attempt++) {
    uint triangleIndex = (baseTriangleIndex + attempt * 97u + attempt * attempt * 13u) % triangleCount;
    uvec4 triangle = skinningTriangles.triangles[pc.draw.y + triangleIndex];
    if (triangle.w >= 2u) {
      continue;
    }

    bool validA = false;
    bool validB = false;
    bool validC = false;
    vec3 a = skin_vertex_at(triangle.x, validA);
    vec3 b = skin_vertex_at(triangle.y, validB);
    vec3 c = skin_vertex_at(triangle.z, validC);
    vec3 bary = anchor_barycentric(anchorIndex + attempt * 4099u);

    bool normalValidA = false;
    bool normalValidB = false;
    bool normalValidC = false;
    vec3 normalA = skin_normal_at(triangle.x, normalValidA);
    vec3 normalB = skin_normal_at(triangle.y, normalValidB);
    vec3 normalC = skin_normal_at(triangle.z, normalValidC);
    bool fallbackNormalValid = normalValidA || normalValidB || normalValidC;
    vec3 fallbackNormal = fallbackNormalValid
        ? safe_normalize(normalA * bary.x + normalB * bary.y + normalC * bary.z, panelForward)
        : panelForward;
    vec3 normalRaw = cross(b - a, c - a);
    float normalLenSq = dot(normalRaw, normalRaw);
    bool degenerateTriangle = normalLenSq <= 0.00000001;
    if (!validA || !validB || !validC) {
      continue;
    }
    if (degenerateTriangle) {
      normalRaw = fallbackNormal;
      normalLenSq = max(dot(normalRaw, normalRaw), 0.00000001);
      particle.debug1 = 1.0;
    }

    particle.center = a * bary.x + b * bary.y + c * bary.z;
    particle.normal = normalRaw * inversesqrt(normalLenSq);
    build_basis(particle.normal, particle.tangent, particle.bitangent);
    particle.valid = true;
    particle.debug0 = float(triangleIndex) / float(max(triangleCount - 1u, 1u));
    if (diagnosticMode == 0u) {
      apply_lche_dynamics(anchorIndex, particle);
    }
    particle.center -= panelForward * pc.liveAdjust.x;
    return particle;
  }
  return particle;
}

SurfaceParticle replay_surface_particle(uint anchorIndex) {
  SurfaceParticle particle = default_particle(anchorIndex);
  particle.valid = true;
  uint diagnosticMode = uint(pc.liveAdjust.z + 0.5);

  uint triangleCount = max(pc.draw.z, 1u);
  uint triangleIndex = (anchorIndex * 2654435761u + anchorIndex / 3u) % triangleCount;
  uint vertexIndex = pc.draw.x + triangleIndex * 3u;
  vec3 a = replayHands.vertices[vertexIndex].position.xyz;
  vec3 b = replayHands.vertices[vertexIndex + 1u].position.xyz;
  vec3 c = replayHands.vertices[vertexIndex + 2u].position.xyz;
  vec3 bary = anchor_barycentric(anchorIndex);
  particle.center = a * bary.x + b * bary.y + c * bary.z;
  particle.normal = safe_normalize(cross(b - a, c - a), vec3(0.0, 0.0, 1.0));
  build_basis(particle.normal, particle.tangent, particle.bitangent);
  particle.debug0 = float(triangleIndex) / float(max(triangleCount - 1u, 1u));
  if (diagnosticMode == 0u) {
    apply_lche_dynamics(anchorIndex, particle);
  }
  return particle;
}

vec2 world_to_panel(vec3 world, vec3 panelCenter, vec3 panelRight, vec3 panelUp, vec3 panelForward, out bool valid, out float depth, out float planeDistance) {
  float targetDistance = max(float(pc._pad0) * 0.000001, 0.20);
  float eyeSign = pc.eyeIndex == 0u ? -1.0 : 1.0;
  vec3 eyePosition = panelCenter - panelForward * targetDistance + panelRight * (eyeSign * REPLAY_HAND_IPD_METERS * 0.5);
  vec3 ray = world - eyePosition;
  depth = dot(ray, panelForward);
  planeDistance = dot(panelCenter - eyePosition, panelForward);
  valid = depth > 0.030 && planeDistance > 0.030;
  float t = planeDistance / max(depth, 0.030);
  vec3 hit = eyePosition + ray * t;
  vec3 rel = hit - panelCenter;
  float halfWidth = max(pc.color.w * 0.5, 0.001);
  float halfHeight = max(pc.panelUpHeight.w * 0.5, 0.001);
  return vec2(dot(rel, panelRight) / halfWidth, dot(rel, panelUp) / halfHeight);
}

void main() {
  uint particleCount = max(pc.draw.w, 1u);
  uint anchorIndex = uint(gl_InstanceIndex) % particleCount;
  vec3 panelCenter = pc.projection.xyz;
  vec3 panelRight = safe_normalize(pc.color.xyz, vec3(1.0, 0.0, 0.0));
  vec3 panelUp = safe_normalize(pc.panelUpHeight.xyz, vec3(0.0, 1.0, 0.0));
  vec3 panelForward = safe_normalize(cross(panelUp, panelRight), vec3(0.0, 0.0, -1.0));
  bool liveFrameReady = pc.dynamics.w > 0.5;
  uint diagnosticMode = uint(pc.liveAdjust.z + 0.5);
  SurfaceParticle particle = liveFrameReady
      ? live_mesh_particle(anchorIndex, panelForward)
      : replay_surface_particle(anchorIndex);

  vec2 quad = QUAD_POSITIONS[uint(gl_VertexIndex) % 6u];
  bool projectionValid = false;
  float depth = 0.0;
  float planeDistance = 1.0;
  vec2 centerPanel = world_to_panel(
      particle.center,
      panelCenter,
      panelRight,
      panelUp,
      panelForward,
      projectionValid,
      depth,
      planeDistance);
  vec2 unclampedCenterPanel = centerPanel;
  bool centerInsidePanel = abs(centerPanel.x) < 1.22 && abs(centerPanel.y) < 1.22;
  if (diagnosticMode == 2u && particle.valid) {
    centerPanel = clamp(centerPanel, vec2(-1.18), vec2(1.18));
  }
  float projectedRadius = particle.radius * (planeDistance / max(depth, 0.030));
  if (diagnosticMode >= 1u) {
    projectedRadius = clamp(projectedRadius * 1.65, 0.0045, 0.028);
  }
  vec2 radiusPanel = vec2(
      projectedRadius / max(pc.color.w * 0.5, 0.001),
      projectedRadius / max(pc.panelUpHeight.w * 0.5, 0.001));
  vec2 panelNdc = centerPanel + quad * radiusPanel;
  bool projectionDiagnosticVisible = diagnosticMode == 2u && particle.valid;
  bool valid = particle.valid && ((projectionValid && centerInsidePanel) || projectionDiagnosticVisible);

  gl_Position = valid ? vec4(panelNdc.x, -panelNdc.y, 0.0, 1.0) : vec4(4.0, 4.0, 0.0, 1.0);

  vec3 viewDirection = safe_normalize((panelCenter - panelForward * max(float(pc._pad0) * 0.000001, 0.20)) - particle.center, -panelForward);
  float facing = clamp(abs(dot(particle.normal, viewDirection)) * 0.55 + 0.45, 0.0, 1.0);
  float shimmer = mix(0.86, 1.14, hash01(anchorIndex + pc.handIndex * 4099u + pc.frameIndex * 17u));
  vec3 rgb = clamp((0.5 + 0.5 * particle.driver) * shimmer * mix(0.82, 1.08, facing), vec3(0.0), vec3(1.0));
  float alpha = mix(0.66, 0.94, particle.anchorPhase) * mix(0.72, 1.0, liveFrameReady ? 1.0 : 0.0);
  if (diagnosticMode == 1u) {
    float band = fract(particle.debug0 * 9.0);
    rgb = clamp(vec3(band, 1.0 - abs(band - 0.5) * 1.7, 1.0 - band) * mix(0.78, 1.18, shimmer), vec3(0.0), vec3(1.0));
    alpha = 1.0;
  } else if (diagnosticMode == 2u) {
    bool wasOffPanel = abs(unclampedCenterPanel.x) >= 1.22 || abs(unclampedCenterPanel.y) >= 1.22;
    rgb = projectionValid
        ? (wasOffPanel ? vec3(1.0, 0.55, 0.05) : vec3(0.1, 1.0, 0.35))
        : vec3(1.0, 0.0, 0.85);
    alpha = 1.0;
  } else if (diagnosticMode == 3u) {
    rgb = liveFrameReady ? vec3(0.92, 1.0, 0.25) : vec3(0.15, 0.85, 1.0);
    alpha = 1.0;
  } else if (diagnosticMode == 4u) {
    rgb = particle.debug1 > 0.5 ? vec3(1.0, 0.05, 0.05) : vec3(0.25, 0.55, 1.0);
    alpha = 1.0;
  }

  outMaskUv = quad * 0.5 + vec2(0.5);
  outColor = vec4(rgb, alpha);
  outParticleParams = vec4(facing, depth, particle.anchorPhase, valid ? 1.0 : 0.0);
}

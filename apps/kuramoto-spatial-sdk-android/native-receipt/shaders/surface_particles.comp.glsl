#version 450

layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;

layout(push_constant) uniform PushConstants {
  float timeSeconds;
  float aspect;
  float pointSizePixels;
  uint particleCount;
  float driver0Value01;
  float driver1Value01;
  float pointScale;
  float reserved0;
} pc;

struct ParticleRow {
  vec4 positionSize;
  vec4 color;
};

layout(set = 0, binding = 0, std430) buffer ParticleBuffer {
  ParticleRow particles[];
} particleBuffer;

vec3 palette(float t) {
  vec3 a = vec3(0.46, 0.40, 0.34);
  vec3 b = vec3(0.44, 0.36, 0.28);
  vec3 c = vec3(1.00, 1.00, 1.00);
  vec3 d = vec3(0.01, 0.34, 0.61);
  return a + b * cos(6.2831853 * (c * t + d));
}

void main() {
  uint index = gl_GlobalInvocationID.x;
  if (index >= pc.particleCount) {
    return;
  }

  float count = max(float(pc.particleCount), 1.0);
  float normalized = (float(index) + 0.5) / count;
  float radius = sqrt(normalized);
  float deformation = mix(0.03, 0.22, clamp(pc.driver0Value01, 0.0, 1.0));
  float coupling = mix(0.08, 0.58, clamp(pc.driver1Value01, 0.0, 1.0));
  float angle = float(index) * 2.39996323 + pc.timeSeconds * (0.22 + coupling);
  float pulse = deformation * sin(pc.timeSeconds * (1.1 + coupling) + float(index) * 0.021);
  float swirl = (0.06 + coupling * 0.16) * sin(pc.timeSeconds * 0.7 + radius * 16.0);
  vec2 position =
      vec2(cos(angle + swirl), sin(angle - swirl)) *
      (0.82 * radius + pulse * (1.0 - radius));
  position.x /= max(pc.aspect, 0.001);

  float size = pc.pointSizePixels * pc.pointScale * (0.70 + 0.85 * (1.0 - radius));
  vec3 color = palette(0.54 + normalized * 0.48 + pc.timeSeconds * (0.014 + coupling * 0.018));
  float alpha = mix(0.72, 0.96, clamp(pc.driver1Value01, 0.0, 1.0));

  particleBuffer.particles[index].positionSize = vec4(position, size, normalized);
  particleBuffer.particles[index].color = vec4(color, alpha);
}

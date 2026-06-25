#version 450

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

layout(location = 0) out vec4 outColor;

struct ParticleRow {
  vec4 positionSize;
  vec4 color;
};

layout(set = 0, binding = 0, std430) readonly buffer ParticleBuffer {
  ParticleRow particles[];
} particleBuffer;

void main() {
  uint index = uint(gl_VertexIndex);
  ParticleRow particle = particleBuffer.particles[index];
  float eyeIndex = clamp(pc.reserved0, 0.0, 1.0);
  if (index < 32u) {
    float slot = float(index);
    float col = mod(slot, 8.0);
    float row = floor(slot / 8.0);
    float markerX = mix(-0.86, 0.58, eyeIndex) + col * 0.04;
    float markerY = 0.84 - row * 0.055;
    vec3 markerColor = mix(vec3(0.10, 0.72, 1.00), vec3(1.00, 0.36, 0.12), eyeIndex);
    gl_Position = vec4(markerX, markerY, 0.0, 1.0);
    gl_PointSize = max(pc.pointSizePixels * 1.35, 12.0);
    outColor = vec4(markerColor, 1.0);
    return;
  }
  gl_Position = vec4(particle.positionSize.xy, 0.0, 1.0);
  gl_PointSize = particle.positionSize.z;
  vec3 eyeTint = mix(vec3(0.48, 0.82, 1.00), vec3(1.00, 0.58, 0.36), eyeIndex);
  vec3 tinted = mix(particle.color.rgb, particle.color.rgb * eyeTint + eyeTint * 0.10, 0.30);
  outColor = vec4(tinted, particle.color.a);
}

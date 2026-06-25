#version 450

layout(location = 0) in vec2 inMaskUv;
layout(location = 1) in vec4 inColor;
layout(location = 2) in vec4 inParticleParams;
layout(location = 0) out vec4 outColor;

void main() {
  vec2 centered = inMaskUv * 2.0 - vec2(1.0);
  float distanceFromCenter = length(centered);
  float softMask = 1.0 - smoothstep(0.18, 1.0, distanceFromCenter);
  float core = 1.0 - smoothstep(0.0, 0.62, distanceFromCenter);
  float valid = inParticleParams.w;
  float alpha = clamp(inColor.a * softMask * valid, 0.0, 1.0);
  vec3 rgb = clamp(inColor.rgb * mix(0.78, 1.22, core), vec3(0.0), vec3(1.0));
  outColor = vec4(rgb * alpha, alpha);
}

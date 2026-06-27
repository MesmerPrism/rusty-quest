#version 450

layout(location = 0) in vec4 inColor;
layout(location = 0) out vec4 outColor;

void main() {
  vec2 point = gl_PointCoord * 2.0 - 1.0;
  float dist2 = dot(point, point);
  if (dist2 > 1.0) {
    discard;
  }
  float alpha = smoothstep(1.0, 0.20, dist2);
  vec3 core = mix(inColor.rgb, vec3(1.0, 0.94, 0.72), smoothstep(0.36, 0.0, dist2));
  outColor = vec4(core, inColor.a * alpha);
}

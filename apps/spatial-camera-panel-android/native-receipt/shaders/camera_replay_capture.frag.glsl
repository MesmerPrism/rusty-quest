#version 450

layout(set = 0, binding = 0) uniform sampler2D u_camera_left;
layout(set = 0, binding = 1) uniform sampler2D u_camera_right;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

void main() {
    bool rightEye = vUv.x >= 0.5;
    vec2 sourceUv = vec2(fract(vUv.x * 2.0), clamp(vUv.y, 0.0, 1.0));
    vec3 rgb = rightEye
        ? texture(u_camera_right, sourceUv).rgb
        : texture(u_camera_left, sourceUv).rgb;
    outColor = vec4(clamp(rgb, vec3(0.0), vec3(1.0)), 1.0);
}

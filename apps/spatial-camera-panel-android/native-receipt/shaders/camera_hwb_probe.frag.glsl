#version 450

layout(set = 0, binding = 0) uniform sampler2D cameraTexture;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

void main() {
    vec2 uv = clamp(vUv, vec2(0.0), vec2(1.0));
    vec3 cameraRgb = texture(cameraTexture, uv).rgb;
    float luma = dot(cameraRgb, vec3(0.299, 0.587, 0.114));

    if (uv.x < 0.5) {
        outColor = vec4(vec3(luma), 1.0);
        return;
    }

    vec2 checkerUv = floor(uv * vec2(32.0, 16.0));
    float checker = mod(checkerUv.x + checkerUv.y, 2.0);
    vec3 overlay = mix(vec3(0.02, 0.18, 0.95), vec3(0.0, 0.9, 0.25), checker);
    outColor = vec4(mix(vec3(luma), overlay, 0.35), 1.0);
}

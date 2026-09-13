#version 450

layout(set = 0, binding = 0) uniform sampler2D u_packed_sbs;

layout(push_constant) uniform PackedSbsNormalizePush {
    uint eyeIndex;
} pc;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

void main() {
    vec2 packedExtent = max(vec2(textureSize(u_packed_sbs, 0)), vec2(2.0, 1.0));
    vec2 halfExtent = vec2(max(floor(packedExtent.x * 0.5), 1.0), packedExtent.y);
    vec2 localUv = clamp(vUv, 0.5 / halfExtent, 1.0 - 0.5 / halfExtent);
    float halfOrigin = pc.eyeIndex == 0u ? 0.0 : 0.5;
    vec2 packedUv = vec2(halfOrigin + localUv.x * 0.5, localUv.y);
    vec2 packedInset = 0.5 / packedExtent;
    packedUv.x = clamp(packedUv.x, halfOrigin + packedInset.x, halfOrigin + 0.5 - packedInset.x);
    packedUv.y = clamp(packedUv.y, packedInset.y, 1.0 - packedInset.y);
    outColor = texture(u_packed_sbs, packedUv);
}

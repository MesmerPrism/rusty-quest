#version 450

layout(set = 0, binding = 0) uniform sampler2D guideTexture;

layout(push_constant) uniform PublicGuideBlurPush {
    vec4 stepAndScale;
    vec4 sourceRect;
} pc;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

void main() {
    vec2 uv = clamp(vUv, vec2(0.0), vec2(1.0));
    vec2 sourceUv = pc.sourceRect.xy + uv * pc.sourceRect.zw;
    vec2 sourceMin = pc.sourceRect.xy;
    vec2 sourceMax = pc.sourceRect.xy + pc.sourceRect.zw;
    vec2 stepUv = pc.stepAndScale.xy;
    vec3 color =
        texture(guideTexture, clamp(sourceUv - 2.0 * stepUv, sourceMin, sourceMax)).rgb * 0.06136 +
        texture(guideTexture, clamp(sourceUv - stepUv, sourceMin, sourceMax)).rgb * 0.24477 +
        texture(guideTexture, sourceUv).rgb * 0.38774 +
        texture(guideTexture, clamp(sourceUv + stepUv, sourceMin, sourceMax)).rgb * 0.24477 +
        texture(guideTexture, clamp(sourceUv + 2.0 * stepUv, sourceMin, sourceMax)).rgb * 0.06136;
    outColor = vec4(color, 1.0);
}

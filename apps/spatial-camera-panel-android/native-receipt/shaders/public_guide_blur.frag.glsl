#version 450

layout(set = 0, binding = 0) uniform sampler2D guideTexture;

layout(push_constant) uniform PublicGuideBlurPush {
    vec4 stepAndScale; // xy: directional step, zw: full packed-target texel size
    vec4 sourceRect;
    vec4 processing; // x: kernel, y: RGB treatment, z: 0 preserve alpha / 1 derive luma
} pc;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

const vec3 PUBLIC_GUIDE_LUMA_WEIGHTS = vec3(0.2126, 0.7152, 0.0722);

vec4 publicGuideInput(vec2 uv) {
    vec4 sampleColor = texture(guideTexture, uv);
    float sourceLuma = clamp(dot(sampleColor.rgb, PUBLIC_GUIDE_LUMA_WEIGHTS), 0.0, 1.0);
    vec3 color = sampleColor.rgb;
    if (pc.processing.y < 0.5) {
        color = vec3(sourceLuma);
    }
    float auxiliary = pc.processing.z < 0.5 ? sampleColor.a : sourceLuma;
    return vec4(color, auxiliary);
}

float publicGuideWeight(int offset) {
    if (pc.processing.x < 0.5) {
        return 0.2;
    }
    const float gaussian5[5] = float[5](0.06136, 0.24477, 0.38774, 0.24477, 0.06136);
    return gaussian5[offset + 2];
}

void main() {
    vec2 uv = clamp(vUv, vec2(0.0), vec2(1.0));
    vec2 sourceUv = pc.sourceRect.xy + uv * pc.sourceRect.zw;
    vec2 texelInset = 0.5 * pc.stepAndScale.zw;
    vec2 sourceMin = pc.sourceRect.xy + texelInset;
    vec2 sourceMax = pc.sourceRect.xy + pc.sourceRect.zw - texelInset;
    vec2 stepUv = pc.stepAndScale.xy;
    vec4 color = vec4(0.0);
    for (int offset = -2; offset <= 2; ++offset) {
        vec2 sampleUv = clamp(sourceUv + float(offset) * stepUv, sourceMin, sourceMax);
        color += publicGuideInput(sampleUv) * publicGuideWeight(offset);
    }
    outColor = clamp(color, vec4(0.0), vec4(1.0));
}

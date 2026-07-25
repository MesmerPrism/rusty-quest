#version 450

layout(set = 0, binding = 0) uniform sampler2D guideTexture;

layout(push_constant) uniform PublicGuideBlurPush {
    vec4 stepAndScale; // xy: directional step, zw: full packed-target texel size
    vec4 sourceRect;
    vec4 processing; // x: 0 native box5 / 1 gaussian5, y: 0 luma / 1 preserve RGB
} pc;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

const vec3 PUBLIC_GUIDE_LUMA_WEIGHTS = vec3(0.2126, 0.7152, 0.0722);

vec3 publicGuideInput(vec2 uv) {
    vec3 color = texture(guideTexture, uv).rgb;
    if (pc.processing.y < 0.5) {
        return vec3(clamp(dot(color, PUBLIC_GUIDE_LUMA_WEIGHTS), 0.0, 1.0));
    }
    return color;
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
    vec3 color = vec3(0.0);
    for (int offset = -2; offset <= 2; ++offset) {
        vec2 sampleUv = clamp(sourceUv + float(offset) * stepUv, sourceMin, sourceMax);
        color += publicGuideInput(sampleUv) * publicGuideWeight(offset);
    }
    outColor = vec4(color, 1.0);
}

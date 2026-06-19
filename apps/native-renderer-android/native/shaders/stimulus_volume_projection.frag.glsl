#version 450

layout(set = 0, binding = 1) uniform sampler2DArray stimulusImage;

layout(push_constant) uniform StimulusVolumePush {
    vec4 params0;
    vec4 params1;
    vec4 params2;
    vec4 params3;
    vec4 params4;
    vec4 params5;
} pushData;

layout(location = 0) in vec2 inUv;
layout(location = 0) out vec4 outColor;

void main() {
    float eyeLayer = clamp(pushData.params2.y, 0.0, 1.0);
    float centralFov = clamp(pushData.params2.w, 0.45, 1.0);
    vec2 centered = inUv * 2.0 - 1.0;
    float edge = max(abs(centered.x), abs(centered.y));
    vec2 stimulusUv = centered / centralFov * 0.5 + 0.5;
    float coverage = centralFov > 0.999
        ? 1.0
        : 1.0 - smoothstep(centralFov, min(1.0, centralFov + 0.025), edge);
    vec3 color = texture(stimulusImage, vec3(clamp(stimulusUv, vec2(0.0), vec2(1.0)), eyeLayer)).rgb;
    outColor = vec4(color * coverage, 1.0);
}

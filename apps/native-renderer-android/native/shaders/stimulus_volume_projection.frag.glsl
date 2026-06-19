#version 450

layout(set = 0, binding = 1) uniform sampler2DArray stimulusImage;

layout(push_constant) uniform StimulusVolumePush {
    vec4 params0;
    vec4 params1;
    vec4 params2;
} pushData;

layout(location = 0) in vec2 inUv;
layout(location = 0) out vec4 outColor;

void main() {
    float eyeLayer = clamp(pushData.params2.y, 0.0, 1.0);
    vec3 color = texture(stimulusImage, vec3(clamp(inUv, vec2(0.0), vec2(1.0)), eyeLayer)).rgb;
    outColor = vec4(color, 1.0);
}

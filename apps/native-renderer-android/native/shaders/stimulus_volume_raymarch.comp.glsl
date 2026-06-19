#version 450

layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

layout(set = 0, binding = 0, rgba8) uniform writeonly image2DArray stimulusImage;

layout(std140, set = 0, binding = 2) uniform StimulusVolumeProfile {
    vec4 sourceA;
    vec4 sourceB;
    vec4 oscillators;
    vec4 depthParams;
    vec4 colorNear;
    vec4 colorMid;
    vec4 colorFar;
} profile;

layout(push_constant) uniform StimulusVolumePush {
    vec4 params0;
    vec4 params1;
    vec4 params2;
} pushData;

float hash31(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

float valueNoise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);

    float n000 = hash31(i + vec3(0.0, 0.0, 0.0));
    float n100 = hash31(i + vec3(1.0, 0.0, 0.0));
    float n010 = hash31(i + vec3(0.0, 1.0, 0.0));
    float n110 = hash31(i + vec3(1.0, 1.0, 0.0));
    float n001 = hash31(i + vec3(0.0, 0.0, 1.0));
    float n101 = hash31(i + vec3(1.0, 0.0, 1.0));
    float n011 = hash31(i + vec3(0.0, 1.0, 1.0));
    float n111 = hash31(i + vec3(1.0, 1.0, 1.0));

    float nx00 = mix(n000, n100, f.x);
    float nx10 = mix(n010, n110, f.x);
    float nx01 = mix(n001, n101, f.x);
    float nx11 = mix(n011, n111, f.x);
    float nxy0 = mix(nx00, nx10, f.y);
    float nxy1 = mix(nx01, nx11, f.y);
    return mix(nxy0, nxy1, f.z);
}

float fbm2(vec3 p) {
    return valueNoise(p) * 0.66 + valueNoise(p * 2.07 + 13.1) * 0.34;
}

vec3 depthRamp(float depth01) {
    vec3 nearToMid = mix(profile.colorNear.rgb, profile.colorMid.rgb, smoothstep(0.0, 0.55, depth01));
    vec3 midToFar = mix(profile.colorMid.rgb, profile.colorFar.rgb, smoothstep(0.42, 1.0, depth01));
    return mix(nearToMid, midToFar, smoothstep(0.35, 0.9, depth01));
}

float wave01(float value) {
    return 0.5 + 0.5 * sin(value);
}

void main() {
    ivec3 pixel = ivec3(gl_GlobalInvocationID.xyz);
    ivec3 size = imageSize(stimulusImage);
    if (pixel.x >= size.x || pixel.y >= size.y || pixel.z >= size.z) {
        return;
    }

    if (pushData.params1.w < 0.5) {
        imageStore(stimulusImage, pixel, vec4(0.0, 0.0, 0.0, 1.0));
        return;
    }

    vec2 uv = (vec2(pixel.xy) + vec2(0.5)) / vec2(size.xy);
    vec2 p = uv * 2.0 - 1.0;
    p.x *= float(size.x) / float(size.y);

    float eyeOffset = pixel.z == 0 ? -0.028 : 0.028;
    vec3 origin = vec3(eyeOffset, -0.015, -0.24);
    vec3 ray = normalize(vec3(p.x * 0.82 + eyeOffset * 0.45, p.y * 0.82, 1.16));

    int sampleCount = int(clamp(profile.depthParams.z, 1.0, 24.0));
    float phase = pushData.params2.z;
    float gain = profile.sourceB.w;
    float blackThreshold = profile.oscillators.w;
    vec3 accumulated = vec3(0.0);
    float peak = 0.0;

    for (int index = 0; index < sampleCount; ++index) {
        float slice01 = (float(index) + 0.5) / float(sampleCount);
        float t = mix(0.18, 1.38, slice01);
        vec3 pos = origin + ray * t;
        pos.xy += 0.045 * vec2(
            sin(phase * 0.31 + slice01 * 6.2831853 + pushData.params1.x),
            cos(phase * 0.23 + slice01 * 5.4977871 + pushData.params1.y)
        );

        float da = length(pos - profile.sourceA.xyz);
        float db = length(pos - profile.sourceB.xyz);
        float noise = fbm2(pos * 4.7 + vec3(pushData.params1.xy, pushData.params1.z));
        float radial = wave01(da * 34.0 - phase * (profile.oscillators.x / max(pushData.params0.w, 0.001)) + pushData.params1.x);
        float axial = wave01((pos.z * 15.0 + pos.y * 5.0) + phase * (profile.oscillators.y / max(pushData.params0.w, 0.001)) + pushData.params1.y);
        float cross = wave01((da - db) * 42.0 + pos.x * 8.0 - phase * (profile.oscillators.z / max(pushData.params0.w, 0.001)) + pushData.params1.z);
        float interference = radial * axial * cross;
        interference = mix(interference, interference * (0.72 + 0.56 * noise), 0.42);

        float gate = smoothstep(blackThreshold, min(0.99, blackThreshold + 0.18), interference);
        gate *= smoothstep(0.04, 0.28, slice01) * smoothstep(1.0, 0.68, slice01);
        vec3 color = depthRamp(slice01) * gate * gain;
        accumulated = max(accumulated, color);
        peak = max(peak, gate);
    }

    float contrast = profile.depthParams.y;
    vec3 color = pow(clamp(accumulated, 0.0, 1.0), vec3(max(0.35, 1.0 - contrast * 0.35)));
    color *= smoothstep(0.02, 0.20, peak);
    imageStore(stimulusImage, pixel, vec4(clamp(color, 0.0, 1.0), 1.0));
}

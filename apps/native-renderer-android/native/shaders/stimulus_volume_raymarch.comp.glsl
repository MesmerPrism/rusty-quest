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
    vec4 qualityParams;
} profile;

layout(push_constant) uniform StimulusVolumePush {
    vec4 params0;
    vec4 params1;
    vec4 params2;
    vec4 params3;
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
    p *= clamp(profile.qualityParams.x, 0.45, 1.0);

    float eyeOffset = pixel.z == 0 ? -0.028 : 0.028;
    vec3 origin = vec3(eyeOffset, -0.015, -0.24);
    vec3 ray = normalize(vec3(p.x * 0.82 + eyeOffset * 0.45, p.y * 0.82, 1.16));

    int sampleCount = int(clamp(profile.depthParams.z, 1.0, 48.0));
    float timeSeconds = pushData.params0.x;
    float phase = pushData.params2.z;
    float radialHz = clamp(pushData.params2.x, 3.0, 40.0);
    float axialHz = clamp(pushData.params2.y, 3.0, 40.0);
    float crossHz = clamp(pushData.params2.w, 3.0, 40.0);
    float spatialScale = clamp(pushData.params0.z, 0.55, 1.90);
    vec2 sourceShift = clamp(pushData.params3.xy, vec2(-0.35), vec2(0.35));
    float noiseScale = clamp(pushData.params3.z, 2.5, 9.5);
    float depthWarp = clamp(pushData.params3.w, 0.0, 0.18);
    vec3 sourceA = profile.sourceA.xyz + vec3(sourceShift.x, sourceShift.y, 0.045 * sin(pushData.params1.z));
    vec3 sourceB = profile.sourceB.xyz + vec3(-sourceShift.x * 0.85, sourceShift.y * 0.55, 0.045 * cos(pushData.params1.y));
    float gain = profile.sourceB.w;
    float blackThreshold = profile.oscillators.w;
    float smoothing = clamp(profile.qualityParams.y, 0.0, 1.0);
    float gateWidth = mix(0.16, 0.34, smoothing);
    vec3 integrated = vec3(0.0);
    vec3 peakColor = vec3(0.0);
    float peak = 0.0;
    float densitySum = 0.0;

    for (int index = 0; index < sampleCount; ++index) {
        float slice01 = (float(index) + 0.5) / float(sampleCount);
        float t = mix(0.18, 1.38, slice01);
        vec3 pos = origin + ray * t;
        pos.xy += (0.035 + depthWarp * 0.55) * vec2(
            sin(phase * 0.31 + slice01 * 6.2831853 + pushData.params1.x),
            cos(phase * 0.23 + slice01 * 5.4977871 + pushData.params1.y)
        );
        pos.z += depthWarp * sin(phase * 0.17 + slice01 * 7.8539816 + pushData.params1.z);

        float da = length(pos - sourceA);
        float db = length(pos - sourceB);
        float noise = fbm2(pos * noiseScale + vec3(pushData.params1.xy, pushData.params1.z));
        float radial = wave01(da * 34.0 * spatialScale - timeSeconds * 6.2831853 * radialHz + pushData.params1.x);
        float axialSpatial = mix(0.72, 1.48, smoothstep(0.0, 0.18, depthWarp));
        float axial = wave01((pos.z * 15.0 + pos.y * 5.0) * axialSpatial + timeSeconds * 6.2831853 * axialHz + pushData.params1.y);
        float crossSpatial = mix(1.58, 0.74, smoothstep(0.55, 1.90, spatialScale));
        float cross = wave01(((da - db) * 42.0 + pos.x * 8.0) * crossSpatial - timeSeconds * 6.2831853 * crossHz + pushData.params1.z);
        float interference = radial * axial * cross;
        interference = mix(interference, interference * (0.70 + 0.64 * noise), 0.46);

        float gate = smoothstep(
            max(0.0, blackThreshold - 0.035 * smoothing),
            min(0.99, blackThreshold + gateWidth),
            interference
        );
        gate *= smoothstep(0.04, 0.28, slice01) * (1.0 - smoothstep(0.68, 1.0, slice01));
        vec3 sampleColor = depthRamp(slice01) * gate * gain;
        integrated += sampleColor * (0.12 + 0.88 * gate);
        peakColor = max(peakColor, sampleColor);
        peak = max(peak, gate);
        densitySum += gate;
    }

    float contrast = profile.depthParams.y;
    float normalization = max(1.0, float(sampleCount) * mix(0.32, 0.46, smoothing));
    vec3 accumulated = mix(peakColor, integrated / normalization, smoothing);
    accumulated *= mix(1.0, 1.0 + min(1.25, densitySum / max(1.0, float(sampleCount))), smoothing);
    vec3 color = pow(clamp(accumulated, 0.0, 1.0), vec3(max(0.35, 1.0 - contrast * 0.35)));
    color *= smoothstep(0.02, 0.20, peak);
    imageStore(stimulusImage, pixel, vec4(clamp(color, 0.0, 1.0), 1.0));
}

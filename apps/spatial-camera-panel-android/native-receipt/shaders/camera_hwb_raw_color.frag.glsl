#version 450

layout(set = 0, binding = 0) uniform sampler2D u_camera_left;
layout(set = 0, binding = 1) uniform sampler2D u_camera_right;

layout(push_constant) uniform CameraHwbProjectionPush {
    vec4 targetRect;
    vec4 params;
    vec4 reprojectionRow0;
    vec4 reprojectionRow1;
    vec4 reprojectionRow2;
    vec4 reprojectionParams;
} pc;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

const float THIN_LINE_AA_MIN_RADIUS_TEXELS = 0.75;
const float THIN_LINE_AA_MAX_RADIUS_TEXELS = 2.0;

bool local_uv_for_rect(vec2 uv, vec4 rect, out vec2 localUv) {
    vec2 size = max(rect.zw, vec2(0.0001));
    localUv = (uv - rect.xy) / size;
    return all(greaterThanEqual(localUv, vec2(0.0))) &&
        all(lessThanEqual(localUv, vec2(1.0)));
}

vec3 fallback_layer_debug(vec3 rgb, vec2 localUv) {
    float layer = pc.params.y;
    if (layer < -0.5 || abs(layer - 8.0) < 0.5) {
        return rgb;
    }
    int layerId = int(clamp(floor(layer + 0.5), 0.0, 6.0));
    float luma = dot(rgb, vec3(0.299, 0.587, 0.114));
    if (layerId == 0) {
        return rgb;
    }
    if (layerId == 1) {
        return vec3(luma);
    }
    if (layerId == 2) {
        return vec3(smoothstep(0.18, 0.82, luma));
    }
    if (layerId == 3) {
        return mix(vec3(luma), vec3(1.0, 0.15, 0.08), clamp(luma, 0.0, 1.0) * 0.55);
    }
    if (layerId == 4) {
        return mix(vec3(luma), vec3(0.05, 0.35, 1.0), smoothstep(0.2, 0.9, luma) * 0.55);
    }
    if (layerId == 5) {
        float grid = step(0.96, max(fract(localUv.x * 18.0), fract(localUv.y * 18.0)));
        return mix(rgb, vec3(0.0, 0.95, 0.75), grid * 0.7);
    }
    float bands = fract((localUv.x + localUv.y) * 10.0);
    return mix(vec3(luma), vec3(bands, 1.0 - bands, 0.55), 0.65);
}

vec2 camera_source_uv_for_presentation(vec2 localUv) {
    float sourceOverscanUv = clamp(pc.params.w, 0.0, 0.2);
    return mix(vec2(sourceOverscanUv), vec2(1.0 - sourceOverscanUv), localUv);
}

vec2 rotation_reprojected_uv(vec2 presentationSourceUv) {
    if (pc.reprojectionParams.x < 0.5) {
        return presentationSourceUv;
    }
    float tanHalfHorizontalFov = max(pc.reprojectionParams.y, 0.01);
    float tanHalfVerticalFov = max(pc.reprojectionParams.z, 0.01);
    vec2 principalPoint = vec2(pc.reprojectionRow0.w, pc.reprojectionRow1.w);
    vec3 currentRay = vec3(
        (presentationSourceUv.x - principalPoint.x) * 2.0 * tanHalfHorizontalFov,
        (principalPoint.y - presentationSourceUv.y) * 2.0 * tanHalfVerticalFov,
        1.0
    );
    vec3 captureRay = vec3(
        dot(pc.reprojectionRow0.xyz, currentRay),
        dot(pc.reprojectionRow1.xyz, currentRay),
        dot(pc.reprojectionRow2.xyz, currentRay)
    );
    float forward = max(captureRay.z, 0.01);
    return vec2(
        principalPoint.x + captureRay.x / (forward * 2.0 * tanHalfHorizontalFov),
        principalPoint.y - captureRay.y / (forward * 2.0 * tanHalfVerticalFov)
    );
}

vec3 camera_sample(sampler2D source, vec2 sampleUv) {
    if (pc.params.x < 0.5) {
        return texture(source, sampleUv).rgb;
    }
    vec2 sourceExtent = max(vec2(textureSize(source, 0)), vec2(1.0));
    vec2 texel = 1.0 / sourceExtent;
    vec2 footprintTexels = max(fwidth(sampleUv) * sourceExtent, vec2(1.0));
    vec2 radiusTexels = clamp(
        footprintTexels * 0.5,
        vec2(THIN_LINE_AA_MIN_RADIUS_TEXELS),
        vec2(THIN_LINE_AA_MAX_RADIUS_TEXELS)
    );
    vec2 d = radiusTexels * texel;
    vec3 color = texture(source, sampleUv).rgb * 0.5;
    color += texture(source, clamp(sampleUv + vec2(-d.x, -d.y), vec2(0.0), vec2(1.0))).rgb * 0.125;
    color += texture(source, clamp(sampleUv + vec2( d.x, -d.y), vec2(0.0), vec2(1.0))).rgb * 0.125;
    color += texture(source, clamp(sampleUv + vec2(-d.x,  d.y), vec2(0.0), vec2(1.0))).rgb * 0.125;
    color += texture(source, clamp(sampleUv + vec2( d.x,  d.y), vec2(0.0), vec2(1.0))).rgb * 0.125;
    return color;
}

void main() {
    vec2 localUv = vec2(0.0);
    if (local_uv_for_rect(vUv, pc.targetRect, localUv)) {
        vec2 presentationSourceUv = camera_source_uv_for_presentation(localUv);
        vec2 sampleUv = rotation_reprojected_uv(presentationSourceUv);
        if (any(lessThan(sampleUv, vec2(0.0))) ||
            any(greaterThan(sampleUv, vec2(1.0)))) {
            discard;
        }
        vec3 rgb = pc.params.z < 0.5
            ? camera_sample(u_camera_left, sampleUv)
            : camera_sample(u_camera_right, sampleUv);
        vec3 color = fallback_layer_debug(rgb, localUv);
        // The raw fallback owns camera sampling only. Center/Middle/Outer owns
        // every interactive boundary, so the fallback must not add a hidden
        // inner fade that cannot be controlled from the region UI.
        outColor = vec4(clamp(color, vec3(0.0), vec3(1.0)), 1.0);
        return;
    }

    discard;
}

#version 450

layout(set = 0, binding = 0) uniform sampler2D u_camera_left;
layout(set = 0, binding = 1) uniform sampler2D u_camera_right;

layout(push_constant) uniform CameraHwbProjectionPush {
    vec4 leftRect;
    vec4 rightRect;
    vec4 params;
} pc;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

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

void main() {
    vec2 localUv = vec2(0.0);
    if (local_uv_for_rect(vUv, pc.leftRect, localUv)) {
        vec3 rgb = texture(u_camera_left, clamp(localUv, vec2(0.0), vec2(1.0))).rgb;
        outColor = vec4(fallback_layer_debug(rgb, localUv), 1.0);
        return;
    }
    if (local_uv_for_rect(vUv, pc.rightRect, localUv)) {
        vec3 rgb = texture(u_camera_right, clamp(localUv, vec2(0.0), vec2(1.0))).rgb;
        outColor = vec4(fallback_layer_debug(rgb, localUv), 1.0);
        return;
    }

    discard;
}

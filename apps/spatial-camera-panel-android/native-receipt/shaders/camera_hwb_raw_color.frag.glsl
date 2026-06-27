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

void main() {
    vec2 localUv = vec2(0.0);
    if (local_uv_for_rect(vUv, pc.leftRect, localUv)) {
        outColor = vec4(texture(u_camera_left, clamp(localUv, vec2(0.0), vec2(1.0))).rgb, 1.0);
        return;
    }
    if (local_uv_for_rect(vUv, pc.rightRect, localUv)) {
        outColor = vec4(texture(u_camera_right, clamp(localUv, vec2(0.0), vec2(1.0))).rgb, 1.0);
        return;
    }

    outColor = vec4(0.0);
}

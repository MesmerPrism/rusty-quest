#version 450

layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

layout(set = 0, binding = 0) uniform sampler2D u_camera_left;
layout(set = 0, binding = 1) uniform sampler2D u_camera_right;

layout(std430, set = 0, binding = 2) buffer LumaDiagnosticOut {
    uvec4 eye_stats[2];
} out_stats;

layout(push_constant) uniform LumaDiagnosticPush {
    uvec4 params0;
    vec4 left_source_uv_rect;
    vec4 right_source_uv_rect;
} pc;

uint quantized_luma(vec3 rgb) {
    float y = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    return uint(round(clamp(y, 0.0, 1.0) * 255.0));
}

void main() {
    uint sample_axis = max(pc.params0.x, 1u);
    uvec3 gid = gl_GlobalInvocationID;
    if (gid.x >= sample_axis || gid.y >= sample_axis || gid.z >= 2u) {
        return;
    }

    vec2 unit_uv = (vec2(gid.xy) + vec2(0.5)) / vec2(sample_axis);
    vec4 source_uv_rect = gid.z == 0u ? pc.left_source_uv_rect : pc.right_source_uv_rect;
    vec2 uv = source_uv_rect.xy + unit_uv * source_uv_rect.zw;
    vec4 center = gid.z == 0u ? texture(u_camera_left, uv) : texture(u_camera_right, uv);
    uint y = quantized_luma(center.rgb);
    atomicAdd(out_stats.eye_stats[gid.z].x, y);
    atomicMax(out_stats.eye_stats[gid.z].y, 255u - y);
    atomicMax(out_stats.eye_stats[gid.z].z, y);

    vec2 step_uv = vec2(source_uv_rect.z / float(sample_axis), 0.0);
    vec2 source_max = source_uv_rect.xy + source_uv_rect.zw;
    vec4 neighbor = gid.z == 0u
        ? texture(u_camera_left, min(uv + step_uv, source_max))
        : texture(u_camera_right, min(uv + step_uv, source_max));
    uint neighbor_y = quantized_luma(neighbor.rgb);
    if (abs(int(y) - int(neighbor_y)) >= int(pc.params0.y)) {
        atomicAdd(out_stats.eye_stats[gid.z].w, 1u);
    }
}

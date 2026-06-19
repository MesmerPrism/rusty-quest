#version 450

layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;

layout(set = 0, binding = 1) buffer EnvironmentDepthParticles {
    vec4 rows[];
} particles;

layout(push_constant) uniform EnvironmentDepthParticlePush {
    vec4 params0;
    vec4 params1;
    vec4 eye_position;
    vec4 eye_orientation_xyzw;
    vec4 fov_tangents;
    vec4 depth_eye_position;
    vec4 depth_eye_orientation_xyzw;
    vec4 depth_fov_tangents;
} pc;

vec4 safe_normalize_quat(vec4 quat) {
    float length_sq = max(dot(quat, quat), 0.000000000001);
    return quat * inversesqrt(length_sq);
}

vec3 rotate_by_quat(vec4 quat, vec3 vector) {
    vec4 q = safe_normalize_quat(quat);
    vec3 uv = cross(q.xyz, vector);
    vec3 uuv = cross(q.xyz, uv);
    return vector + uv * (2.0 * q.w) + uuv * 2.0;
}

float hash01(uint seed) {
    seed ^= seed >> 16u;
    seed *= 2246822519u;
    seed ^= seed >> 13u;
    seed *= 3266489917u;
    seed ^= seed >> 16u;
    return float(seed & 0x00ffffffu) / float(0x01000000u);
}

void main() {
    uint index = gl_GlobalInvocationID.x;
    uint particle_count = uint(max(pc.params0.x, 0.0));
    if (index >= particle_count) {
        return;
    }

    float radius_m = max(pc.params0.y, 0.001);
    float time_s = pc.params0.z;
    float alpha = clamp(pc.params0.w, 0.05, 1.0);
    float near_m = max(pc.params1.x, 0.05);
    float far_m = max(pc.params1.y, near_m + 0.05);
    float stride_hint = max(pc.params1.w, 1.0);

    uint columns = max(uint(ceil(sqrt(float(max(particle_count, 1u))) * 1.35)), 1u);
    uint rows = max((particle_count + columns - 1u) / columns, 1u);
    uint column = index % columns;
    uint row = index / columns;

    float u = (float(column) + 0.5) / float(columns);
    float v = (float(row) + 0.5) / float(rows);
    float x_tan = mix(pc.fov_tangents.x, pc.fov_tangents.y, u);
    float y_tan = mix(pc.fov_tangents.w, pc.fov_tangents.z, v);
    float wave = 0.5 + 0.5 * sin(float(column) * 0.37 + float(row) * 0.23 + time_s * 1.3);
    float noise = hash01(index * 1664525u + 1013904223u);
    float depth01 = clamp(0.20 + wave * 0.55 + noise * 0.12, 0.0, 1.0);
    float depth_m = mix(near_m, far_m, depth01);

    vec3 depth_view = vec3(x_tan * depth_m, y_tan * depth_m, -depth_m);
    vec3 reference_space_point =
        pc.eye_position.xyz + rotate_by_quat(pc.eye_orientation_xyzw, depth_view);

    float column_tint = float(column) / float(max(columns - 1u, 1u));
    float row_tint = float(row) / float(max(rows - 1u, 1u));
    vec3 near_color = vec3(0.10, 0.92, 1.00);
    vec3 far_color = vec3(1.00, 0.72, 0.20);
    vec3 color = mix(near_color, far_color, depth01);
    color *= mix(0.82, 1.16, hash01(index + 17u));

    uint base = index * 4u;
    particles.rows[base] = vec4(reference_space_point, radius_m);
    particles.rows[base + 1u] = vec4(clamp(color, vec3(0.0), vec3(1.0)), alpha);
    particles.rows[base + 2u] = vec4(x_tan, y_tan, depth_m, 1.0);
    particles.rows[base + 3u] = vec4(float(index), column_tint, row_tint, stride_hint);
}

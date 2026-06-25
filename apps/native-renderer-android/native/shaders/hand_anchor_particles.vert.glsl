#version 450

layout(set = 0, binding = 0) readonly buffer SkinnedWorldPositions {
    vec4 positions[];
} skinned_positions;

layout(set = 0, binding = 1) readonly buffer RecordedSkinningTriangles {
    uvec4 triangles[];
} skinning_triangles;

layout(set = 0, binding = 2) readonly buffer ParticleOutput {
    vec4 rows[];
} particle_output;

layout(set = 0, binding = 3) readonly buffer ParticleSortRows {
    uvec4 rows[];
} particle_sort;

layout(push_constant) uniform HandAnchorParticlePush {
    vec4 params0;
    vec4 params1;
    vec4 params2;
    vec4 eye_position;
    vec4 eye_orientation_xyzw;
    vec4 fov_tangents;
    vec4 target0;
    vec4 target1;
} pc;

layout(location = 0) out vec2 v_mask_uv;
layout(location = 1) out vec4 v_color;
layout(location = 2) out vec4 v_render_params;

const vec2 QUAD_POSITIONS[6] = vec2[](
    vec2(-1.0, -1.0),
    vec2( 1.0, -1.0),
    vec2(-1.0,  1.0),
    vec2(-1.0,  1.0),
    vec2( 1.0, -1.0),
    vec2( 1.0,  1.0)
);

vec4 safe_normalize_quat(vec4 quat) {
    float length_sq = max(dot(quat, quat), 0.000000000001);
    return quat * inversesqrt(length_sq);
}

vec4 inverse_quat(vec4 quat) {
    vec4 q = safe_normalize_quat(quat);
    return vec4(-q.xyz, q.w);
}

vec3 rotate_by_quat(vec4 quat, vec3 vector) {
    vec4 q = safe_normalize_quat(quat);
    vec3 uv = cross(q.xyz, vector);
    vec3 uuv = cross(q.xyz, uv);
    return vector + uv * (2.0 * q.w) + uuv * 2.0;
}

vec3 world_to_eye(vec3 world) {
    return rotate_by_quat(
        inverse_quat(pc.eye_orientation_xyzw),
        world - pc.eye_position.xyz
    );
}

vec4 world_to_eye_clip(vec3 world) {
    vec3 eye = world_to_eye(world);
    if (eye.z >= -0.0001) {
        return vec4(4.0, 4.0, 0.0, 1.0);
    }
    float forward = -eye.z;
    float x_over_z = eye.x / forward;
    float y_over_z = eye.y / forward;
    float left_tan = pc.fov_tangents.x;
    float right_tan = pc.fov_tangents.y;
    float down_tan = pc.fov_tangents.z;
    float up_tan = pc.fov_tangents.w;
    float ndc_x = ((x_over_z - left_tan) / max(right_tan - left_tan, 0.0001)) * 2.0 - 1.0;
    float screen_y = 1.0 - ((y_over_z - down_tan) / max(up_tan - down_tan, 0.0001));
    float ndc_y = screen_y * 2.0 - 1.0;
    return vec4(ndc_x, ndc_y, 0.0, 1.0);
}

vec3 recorded_fallback_to_camera_front(vec3 recorded, float hand_sign) {
    vec3 source_center = vec3(pc.target0.x, pc.target0.y, pc.target1.x);
    float source_radius = max(pc.target0.w, 0.0001);
    vec3 local = (recorded - source_center) / source_radius;

    vec3 eye_right = rotate_by_quat(pc.eye_orientation_xyzw, vec3(1.0, 0.0, 0.0));
    vec3 eye_up = rotate_by_quat(pc.eye_orientation_xyzw, vec3(0.0, 1.0, 0.0));
    vec3 eye_forward = rotate_by_quat(pc.eye_orientation_xyzw, vec3(0.0, 0.0, -1.0));

    float placement_distance_m = 0.78;
    float hand_scale_m = 0.125;
    vec3 hand_center = pc.eye_position.xyz
        + eye_forward * placement_distance_m
        + eye_right * (hand_sign * 0.150)
        + eye_up * -0.220;

    return hand_center
        + eye_right * (local.x * hand_scale_m)
        + eye_up * (local.y * hand_scale_m)
        + eye_forward * (local.z * hand_scale_m * 0.55);
}

float hash01(uint seed) {
    seed ^= seed >> 16u;
    seed *= 2246822519u;
    seed ^= seed >> 13u;
    seed *= 3266489917u;
    seed ^= seed >> 16u;
    return float(seed & 0x00ffffffu) / float(0x01000000u);
}

vec3 anchor_barycentric(uint anchor_index) {
    float u = hash01(anchor_index * 1664525u + 1013904223u);
    float v = hash01(anchor_index * 22695477u + 1u);
    float su = sqrt(u);
    return vec3(1.0 - su, su * (1.0 - v), su * v);
}

void main() {
    uint triangle_count = max(uint(pc.params0.x), 1u);
    uint anchor_count = max(uint(pc.params0.y), 1u);
    float radius = max(pc.params0.z, 0.0002);
    uint hand_code = uint(pc.params0.w);
    bool use_particle_output = pc.params1.x > 0.5;
    bool use_sort_remap = pc.params2.x > 0.5;
    bool recorded_fallback_placement = pc.params2.y > 0.5;
    float fallback_hand_sign = pc.params2.z;

    uint anchor_index = use_sort_remap
        ? particle_sort.rows[gl_InstanceIndex].x
        : gl_InstanceIndex;
    vec3 center;
    vec4 particle_color;
    if (use_particle_output) {
        uint row = anchor_index * 4u;
        vec4 position_radius = particle_output.rows[row];
        center = position_radius.xyz;
        radius = max(position_radius.w, 0.0002);
        particle_color = particle_output.rows[row + 1u];
    } else {
        uint triangle_index = (anchor_index * 2654435761u + anchor_index / 3u) % triangle_count;
        uvec4 triangle = skinning_triangles.triangles[triangle_index];

        vec3 bary = anchor_barycentric(anchor_index);
        vec3 a = skinned_positions.positions[triangle.x].xyz;
        vec3 b = skinned_positions.positions[triangle.y].xyz;
        vec3 c = skinned_positions.positions[triangle.z].xyz;
        center = a * bary.x + b * bary.y + c * bary.z;

        float hand_mix = hand_code == 2u ? 1.0 : 0.0;
        vec3 left_color = vec3(0.96, 1.00, 0.78);
        vec3 right_color = vec3(0.70, 0.91, 1.00);
        float anchor_phase = float(anchor_index) / float(anchor_count);
        vec3 color = mix(left_color, right_color, hand_mix);
        color *= mix(0.86, 1.10, hash01(anchor_index + 17u));
        particle_color = vec4(clamp(color, vec3(0.0), vec3(1.0)), mix(0.74, 1.0, anchor_phase));
    }

    if (recorded_fallback_placement) {
        center = recorded_fallback_to_camera_front(center, fallback_hand_sign);
    }

    vec2 quad = QUAD_POSITIONS[gl_VertexIndex % 6];
    vec3 eye_right = rotate_by_quat(pc.eye_orientation_xyzw, vec3(1.0, 0.0, 0.0));
    vec3 eye_up = rotate_by_quat(pc.eye_orientation_xyzw, vec3(0.0, 1.0, 0.0));
    vec3 world = center + (eye_right * quad.x + eye_up * quad.y) * radius;
    vec3 center_eye = world_to_eye(center);
    float center_forward_m = max(-center_eye.z, 0.0);

    v_mask_uv = quad * 0.5 + vec2(0.5);
    v_color = vec4(clamp(particle_color.rgb, vec3(0.0), vec3(1.0)), clamp(particle_color.a, 0.0, 1.0));
    v_render_params = vec4(pc.params1.y, pc.params1.z, pc.params1.w, center_forward_m);
    gl_Position = world_to_eye_clip(world);
}

#version 450

layout(set = 0, binding = 2) readonly buffer PrivateParticleRows {
    vec4 rows[];
} particle_output;

layout(set = 0, binding = 7) readonly buffer PrivateParticleSortRows {
    uvec4 rows[];
} particle_sort;

layout(push_constant) uniform PrivateParticlePush {
    vec4 params0;
    vec4 params1;
    vec4 transparency_params;
    vec4 tracer_params;
    vec4 world_center_scale;
    vec4 eye_position;
    vec4 eye_orientation_xyzw;
    vec4 fov_tangents;
} pc;

layout(location = 0) out vec2 v_mask_uv;
layout(location = 1) out vec4 v_color;
layout(location = 2) out vec4 v_render_params;
layout(location = 3) out vec4 v_color_params;

const float NEAR_M = 0.05;
const float FAR_DEPTH_SPAN_M = 12.0;

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

void main() {
    uint draw_index = gl_InstanceIndex;
    uint particle_count = max(uint(pc.params0.x), 1u);
    uint draw_count = max(uint(pc.tracer_params.x), 1u);
    uint packed_mode = uint(pc.params0.z + 0.5);
    uint ordering_mode = (packed_mode / 10u) % 10u;
    float facing_attenuation_strength =
        clamp(float((packed_mode / 100u) % 10000u) / 1000.0, 0.0, 1.0);
    uint index;
    if (ordering_mode == 1u) {
        index = min(draw_index, draw_count - 1u);
    } else {
        uint sort_rank = min(draw_index, draw_count - 1u);
        index = min(particle_sort.rows[sort_rank].x, draw_count - 1u);
    }
    uint base = index * 4u;
    vec4 position_radius = particle_output.rows[base];
    vec4 color_alpha = particle_output.rows[base + 1u];
    vec4 normal_flags = particle_output.rows[base + 2u];
    vec4 aux = particle_output.rows[base + 3u];
    vec2 raw_quad = QUAD_POSITIONS[gl_VertexIndex % 6];
    float rotation = aux.x;
    float cs = cos(rotation);
    float sn = sin(rotation);
    vec2 quad = vec2(
        raw_quad.x * cs - raw_quad.y * sn,
        raw_quad.x * sn + raw_quad.y * cs
    );

    float radius_m = max(position_radius.w, 0.0005);
    vec3 eye_right = rotate_by_quat(pc.eye_orientation_xyzw, vec3(1.0, 0.0, 0.0));
    vec3 eye_up = rotate_by_quat(pc.eye_orientation_xyzw, vec3(0.0, 1.0, 0.0));
    vec3 world = position_radius.xyz + (eye_right * quad.x + eye_up * quad.y) * radius_m;
    vec3 center_eye = world_to_eye(position_radius.xyz);
    float center_forward_m = max(-center_eye.z, 0.0);
    bool valid = normal_flags.w > 0.5 && color_alpha.a > 0.002 && center_eye.z < -0.0001;

    v_mask_uv = raw_quad * 0.5 + vec2(0.5);
    v_color = valid ? color_alpha : vec4(0.0);
    v_render_params = vec4(center_forward_m, clamp(aux.y, 0.0, 0.99902344), aux.z, aux.w);
    // This is sphere-surface facing, not billboard facing. Billboards always
    // face the eye; this value lets the fragment shader optionally darken
    // particles whose icosphere normal points away from the eye.
    vec3 view_dir = normalize(pc.eye_position.xyz - position_radius.xyz);
    float normal_len_sq = max(dot(normal_flags.xyz, normal_flags.xyz), 0.000001);
    vec3 safe_normal = normal_flags.xyz * inversesqrt(normal_len_sq);
    float facing = valid ? clamp(dot(safe_normal, view_dir), 0.0, 1.0) : 1.0;
    float depth_suppression_strength = clamp(pc.transparency_params.z, 0.0, 8.0);
    float depth01 = clamp((center_forward_m - NEAR_M) / FAR_DEPTH_SPAN_M, 0.0, 1.0);
    float depth_atten = exp2(-depth_suppression_strength * depth01);
    // Optional surface-normal RGB attenuation. Strength 0.0 leaves color
    // unchanged; strength 0.20 reproduces the old 0.80 + 0.20 * facing look;
    // strength 1.0 makes side/back sphere-surface particles darkest.
    float facing_atten = 1.0 - facing_attenuation_strength * (1.0 - facing);
    v_color_params = vec4(depth_atten * facing_atten, facing, depth_atten, facing_atten);
    gl_Position = valid ? world_to_eye_clip(world) : vec4(4.0, 4.0, 0.0, 1.0);
}

#version 450

layout(set = 0, binding = 1) readonly buffer EnvironmentDepthParticles {
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

layout(location = 0) out vec2 v_mask_uv;
layout(location = 1) out vec4 v_color;
layout(location = 2) out vec4 v_render_params;

const uint DEPTH_FLAG_SCENE_PARTICLE_MAP = 2u;
const float SCENE_PARTICLE_FADE_START_FRAMES = 720.0;
const float SCENE_PARTICLE_RETIRE_FRAMES = 1440.0;

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

bool scene_particle_map_requested() {
    uint flags = uint(max(floor(pc.params1.z + 0.5), 0.0));
    return (flags & DEPTH_FLAG_SCENE_PARTICLE_MAP) != 0u;
}

float scene_particle_age_alpha(vec4 particle_state) {
    if (!scene_particle_map_requested()) {
        return 1.0;
    }
    float age_frames = max(pc.eye_position.w - particle_state.z, 0.0);
    return 1.0 - smoothstep(
        SCENE_PARTICLE_FADE_START_FRAMES,
        SCENE_PARTICLE_RETIRE_FRAMES,
        age_frames);
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
    uint base = gl_InstanceIndex * 4u;
    vec4 position_radius = particles.rows[base];
    vec4 particle_color = particles.rows[base + 1u];
    vec4 source_sample = particles.rows[base + 2u];
    vec4 particle_state = particles.rows[base + 3u];
    float age_alpha = scene_particle_age_alpha(particle_state);
    bool valid = source_sample.w >= 0.5 && particle_color.a > 0.002 && age_alpha > 0.01;

    float radius_m = max(position_radius.w, 0.0005);
    vec2 quad = QUAD_POSITIONS[gl_VertexIndex % 6];
    vec3 eye_right = rotate_by_quat(pc.eye_orientation_xyzw, vec3(1.0, 0.0, 0.0));
    vec3 eye_up = rotate_by_quat(pc.eye_orientation_xyzw, vec3(0.0, 1.0, 0.0));
    vec3 world = position_radius.xyz + (eye_right * quad.x + eye_up * quad.y) * radius_m;
    vec3 center_eye = world_to_eye(position_radius.xyz);
    float center_forward_m = max(-center_eye.z, 0.0);
    if (!valid || center_eye.z >= -0.0001) {
        gl_Position = vec4(4.0, 4.0, 0.0, 1.0);
        v_mask_uv = quad * 0.5 + vec2(0.5);
        v_color = vec4(0.0);
        v_render_params = vec4(center_forward_m, source_sample.z, pc.params1.x, pc.params1.y);
        return;
    }

    v_mask_uv = quad * 0.5 + vec2(0.5);
    v_color = vec4(
        clamp(particle_color.rgb, vec3(0.0), vec3(1.0)),
        clamp(particle_color.a * age_alpha, 0.0, 1.0));
    v_render_params = vec4(center_forward_m, source_sample.z, pc.params1.x, pc.params1.y);
    gl_Position = world_to_eye_clip(world);
}

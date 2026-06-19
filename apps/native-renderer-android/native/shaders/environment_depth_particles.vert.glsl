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
const float DEBUG_COLOR_DEPTH_GRADIENT = 0.0;
const float DEBUG_COLOR_CONFIDENCE = 1.0;
const float DEBUG_COLOR_AGE = 2.0;
const float DEBUG_COLOR_SOURCE_LAYER = 3.0;
const float DEBUG_COLOR_HASH_PROBE = 4.0;
const float DEBUG_COLOR_FREE_SPACE_STATE = 5.0;
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

float particle_debug_color_mode() {
    return floor(pc.params0.z + 0.5);
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

float scene_particle_age01(vec4 particle_state) {
    if (!scene_particle_map_requested()) {
        return 0.0;
    }
    float age_frames = max(pc.eye_position.w - particle_state.z, 0.0);
    return clamp(age_frames / max(SCENE_PARTICLE_RETIRE_FRAMES, 1.0), 0.0, 1.0);
}

float active_probe01(vec4 particle_state) {
    return clamp((particle_state.w - 1.0) * 2.0, 0.0, 1.0);
}

bool scene_particle_retired(vec4 source_sample, vec4 particle_state) {
    return scene_particle_map_requested()
        && source_sample.w < 0.5
        && particle_state.w >= 1.75;
}

vec3 heat_color(float value) {
    vec3 low = vec3(0.05, 0.18, 1.00);
    vec3 mid = vec3(0.04, 0.95, 0.42);
    vec3 high = vec3(1.00, 0.24, 0.08);
    float t = clamp(value, 0.0, 1.0);
    return t < 0.5
        ? mix(low, mid, t * 2.0)
        : mix(mid, high, (t - 0.5) * 2.0);
}

vec4 debug_particle_color(
    vec4 particle_color,
    vec4 source_sample,
    vec4 particle_state,
    float age_alpha
) {
    float mode = particle_debug_color_mode();
    float confidence = clamp(particle_state.y, 0.0, 1.0);
    float source_layer = max(source_sample.w - 1.0, 0.0);
    float age01 = scene_particle_age01(particle_state);
    bool retired = scene_particle_retired(source_sample, particle_state);

    if (mode == DEBUG_COLOR_CONFIDENCE) {
        return vec4(heat_color(1.0 - confidence), clamp(mix(0.28, 0.95, confidence), 0.0, 1.0));
    }
    if (mode == DEBUG_COLOR_AGE) {
        return vec4(heat_color(age01), clamp(mix(0.95, 0.25, age01), 0.0, 1.0));
    }
    if (mode == DEBUG_COLOR_SOURCE_LAYER) {
        vec3 layer0 = vec3(0.04, 0.88, 1.00);
        vec3 layer1 = vec3(1.00, 0.16, 0.88);
        return vec4(mix(layer0, layer1, clamp(source_layer, 0.0, 1.0)), 0.86);
    }
    if (mode == DEBUG_COLOR_HASH_PROBE) {
        return vec4(heat_color(active_probe01(particle_state)), 0.86);
    }
    if (mode == DEBUG_COLOR_FREE_SPACE_STATE) {
        return retired
            ? vec4(1.00, 0.12, 0.04, 0.55)
            : vec4(0.04, 1.00, 0.34, clamp(particle_color.a * max(age_alpha, 0.28), 0.0, 1.0));
    }
    return vec4(
        clamp(particle_color.rgb, vec3(0.0), vec3(1.0)),
        clamp(particle_color.a * age_alpha, 0.0, 1.0));
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
    bool retired_debug = particle_debug_color_mode() == DEBUG_COLOR_FREE_SPACE_STATE
        && scene_particle_retired(source_sample, particle_state);
    bool valid = (source_sample.w >= 0.5 && particle_color.a > 0.002 && age_alpha > 0.01)
        || retired_debug;

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
    v_color = debug_particle_color(particle_color, source_sample, particle_state, age_alpha);
    v_render_params = vec4(center_forward_m, source_sample.z, pc.params1.x, pc.params1.y);
    gl_Position = world_to_eye_clip(world);
}

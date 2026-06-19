#version 450

layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

layout(set = 0, binding = 0) uniform sampler2DArray u_environment_depth;

layout(set = 0, binding = 1) buffer EnvironmentDepthParticles {
    vec4 rows[];
} particles;

layout(set = 0, binding = 2) buffer EnvironmentDepthRawDebugStats {
    uint values[];
} depth_debug;

layout(set = 0, binding = 3) buffer EnvironmentDepthSceneMapMetadata {
    uint words[];
} scene_meta;

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

const uint DEPTH_FLAG_INFINITE_FAR = 1u;
const uint DEPTH_FLAG_SCENE_PARTICLE_MAP = 2u;
const uint DEPTH_FLAG_SOURCE_LAYER1 = 4u;
const uint DEPTH_FLAG_SURFACE_SUPPORT_ENFORCED = 8u;
const uint DEPTH_FLAG_SURFACE_SUPPORT_MIN_NEIGHBOR_MASK = 0x0000ff00u;
const uint DEPTH_FLAG_SURFACE_SUPPORT_MIN_NEIGHBOR_SHIFT = 8u;
const uint DEPTH_FLAG_SURFACE_SUPPORT_RADIUS_MASK = 0x000f0000u;
const uint DEPTH_FLAG_SURFACE_SUPPORT_RADIUS_SHIFT = 16u;
const float SCENE_PARTICLE_CELL_METERS = 0.06;
const uint SCENE_PARTICLE_PROBE_COUNT = 8u;
const float SCENE_PARTICLE_STALE_REPLACE_FRAMES = 1440.0;
const float SCENE_PARTICLE_MERGE_WEIGHT = 0.18;
const float SCENE_PARTICLE_CONFIDENCE_THRESHOLD = 0.58;
const float SCENE_PARTICLE_SURFACE_SUPPORT_MIN_DEPTH_TOLERANCE_METERS = 0.18;
const float SCENE_PARTICLE_SURFACE_SUPPORT_DEPTH_TOLERANCE_SCALE = 0.10;
const float SCENE_PARTICLE_ACTIVE_CORRECTION_CONFIDENCE = 0.78;
const float SCENE_PARTICLE_ACTIVE_CORRECTION_STEP_METERS = SCENE_PARTICLE_CELL_METERS;
const uint SCENE_PARTICLE_ACTIVE_CORRECTION_MAX_STEPS = 64u;
const float SCENE_PARTICLE_ACTIVE_CORRECTION_SURFACE_KEEP_METERS = 0.18;
const uint RAW_DEBUG_VALID_COUNT = 0u;
const uint RAW_DEBUG_INVALID_COUNT = 1u;
const uint RAW_DEBUG_CONFIDENCE_REJECTED_COUNT = 2u;
const uint RAW_DEBUG_CENTER_D16 = 3u;
const uint RAW_DEBUG_CENTER_RECONSTRUCTED_MM = 4u;
const uint RAW_DEBUG_CENTER_CONFIDENCE_MILLI = 5u;
const uint RAW_DEBUG_CENTER_MEDIAN_D16 = 6u;
const uint RAW_DEBUG_MIN_VALID_INVERSE_MM = 7u;
const uint RAW_DEBUG_MAX_VALID_MM = 8u;
const uint RAW_DEBUG_CENTER_WINDOW_VALID_COUNT = 9u;
const uint RAW_DEBUG_HASH_INSERT_SUCCESS_COUNT = 10u;
const uint RAW_DEBUG_HASH_MERGE_COUNT = 11u;
const uint RAW_DEBUG_HASH_STALE_REPLACE_COUNT = 12u;
const uint RAW_DEBUG_HASH_PROBE_EXHAUSTED_COUNT = 13u;
const uint RAW_DEBUG_FREE_SPACE_RETIRE_ATTEMPT_COUNT = 14u;
const uint RAW_DEBUG_FREE_SPACE_RETIRE_SUCCESS_COUNT = 15u;
const uint RAW_DEBUG_HASH_OCCUPANCY_ESTIMATE = 16u;
const uint RAW_DEBUG_HASH_WRITE_CONFLICT_COUNT = 17u;
const uint RAW_DEBUG_HASH_CLAIM_FAILED_COUNT = 18u;
const uint RAW_DEBUG_FREE_SPACE_CONFIDENCE_SKIPPED_COUNT = 19u;
const uint RAW_DEBUG_SURFACE_SUPPORTED_CELLS = 20u;
const uint RAW_DEBUG_SURFACE_REJECTED_ISOLATED_CELLS = 21u;
const uint SCENE_META_WORDS_PER_SLOT = 4u;
const uint SCENE_META_KEY = 0u;
const uint SCENE_META_STATE = 1u;
const uint SCENE_META_LAST_FRAME = 2u;
const uint SCENE_META_FLAGS = 3u;
const uint SCENE_META_STATE_EMPTY = 0u;
const uint SCENE_META_STATE_ACTIVE = 1u;
const uint SCENE_META_STATE_RETIRED = 2u;
const uint SCENE_META_STATE_WRITING = 3u;

uint depth_flags() {
    return uint(max(floor(pc.params1.z + 0.5), 0.0));
}

bool infinite_far_requested() {
    return (depth_flags() & DEPTH_FLAG_INFINITE_FAR) != 0u;
}

bool scene_particle_map_requested() {
    return (depth_flags() & DEPTH_FLAG_SCENE_PARTICLE_MAP) != 0u;
}

bool surface_support_enforced_requested() {
    return (depth_flags() & DEPTH_FLAG_SURFACE_SUPPORT_ENFORCED) != 0u;
}

uint surface_support_min_neighbor_count() {
    return (depth_flags() & DEPTH_FLAG_SURFACE_SUPPORT_MIN_NEIGHBOR_MASK)
        >> DEPTH_FLAG_SURFACE_SUPPORT_MIN_NEIGHBOR_SHIFT;
}

uint surface_support_radius_cells() {
    uint radius = (depth_flags() & DEPTH_FLAG_SURFACE_SUPPORT_RADIUS_MASK)
        >> DEPTH_FLAG_SURFACE_SUPPORT_RADIUS_SHIFT;
    return clamp(radius, 1u, 8u);
}

float depth_source_layer_index() {
    return ((depth_flags() & DEPTH_FLAG_SOURCE_LAYER1) != 0u) ? 1.0 : 0.0;
}

float frame_marker() {
    return max(pc.eye_position.w, 0.0);
}

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

vec2 apply_depth_texture_transform(vec2 uv, int flags) {
    int turns = flags & 3;
    if (turns == 1) {
        uv = vec2(uv.y, 1.0 - uv.x);
    } else if (turns == 2) {
        uv = vec2(1.0 - uv.x, 1.0 - uv.y);
    } else if (turns == 3) {
        uv = vec2(1.0 - uv.y, uv.x);
    }
    if ((flags & 4) != 0 || (flags & 16) != 0) {
        uv.x = 1.0 - uv.x;
    }
    if ((flags & 8) != 0) {
        uv.y = 1.0 - uv.y;
    }
    return uv;
}

float sample_raw_depth(vec2 uv) {
    return textureLod(
        u_environment_depth,
        vec3(clamp(uv, vec2(0.0), vec2(1.0)), depth_source_layer_index()),
        0.0).r;
}

bool raw_depth_is_valid(float raw_depth) {
    return raw_depth >= 0.0 && raw_depth < 1.0 - (0.5 / 65535.0);
}

float raw_depth_to_meters(float raw_depth) {
    float near_z = max(pc.params1.x, 0.001);
    float far_z = pc.params1.y;
    bool infinite_far = infinite_far_requested() || !(far_z > near_z);
    raw_depth = clamp(raw_depth, 0.0, 1.0);

    if (infinite_far) {
        return near_z / max(1.0 - raw_depth, 1.0 / 65535.0);
    }

    return (near_z * far_z) / max(far_z - raw_depth * (far_z - near_z), 0.0001);
}

float sample_depth_meters(vec2 uv) {
    float raw_depth = sample_raw_depth(uv);
    if (!raw_depth_is_valid(raw_depth)) {
        return pc.params1.y + 1.0;
    }
    return raw_depth_to_meters(raw_depth);
}

vec3 reconstruct_reference_space_position(vec2 surface_uv, float depth_meters) {
    float tangent_x = mix(pc.depth_fov_tangents.x, pc.depth_fov_tangents.y, surface_uv.x);
    float tangent_y = mix(pc.depth_fov_tangents.z, pc.depth_fov_tangents.w, surface_uv.y);
    vec3 depth_view = vec3(tangent_x * depth_meters, tangent_y * depth_meters, -depth_meters);
    return pc.depth_eye_position.xyz + rotate_by_quat(pc.depth_eye_orientation_xyzw, depth_view);
}

vec3 depth_color(float depth_meters) {
    float depth01 = clamp(
        (depth_meters - pc.params1.x) / max(pc.params1.y - pc.params1.x, 0.01),
        0.0,
        1.0);
    vec3 near_color = vec3(0.10, 0.92, 1.00);
    vec3 far_color = vec3(1.00, 0.72, 0.20);
    return mix(near_color, far_color, depth01);
}

float source_valid_marker() {
    return 1.0 + depth_source_layer_index();
}

float active_scene_state_marker(uint probe) {
    return 1.0 + 0.5 * (float(probe) / float(max(SCENE_PARTICLE_PROBE_COUNT - 1u, 1u)));
}

void write_retained_invalid(uint slot) {
    uint base = slot * 4u;
    particles.rows[base] = vec4(0.0);
    particles.rows[base + 1u] = vec4(0.0);
    particles.rows[base + 2u] = vec4(0.0);
    particles.rows[base + 3u] = vec4(0.0);
}

void write_particle_slot(
    uint slot,
    vec3 reference_space_point,
    float depth_meters,
    float confidence,
    vec2 surface_uv,
    vec2 depth_uv,
    float slot_key,
    float state_marker
) {
    uint base = slot * 4u;
    vec3 color = depth_color(depth_meters);
    particles.rows[base] = vec4(reference_space_point, max(pc.params0.y, 0.0005));
    particles.rows[base + 1u] = vec4(clamp(color, vec3(0.0), vec3(1.0)), pc.params0.w * confidence);
    particles.rows[base + 2u] = vec4(surface_uv, depth_meters, source_valid_marker());
    particles.rows[base + 3u] = vec4(slot_key, confidence, frame_marker(), state_marker + depth_uv.x * 0.0);
}

uint hash_scene_cell(ivec3 cell) {
    uint h = (uint(cell.x) * 73856093u)
        ^ (uint(cell.y) * 19349663u)
        ^ (uint(cell.z) * 83492791u);
    h ^= h >> 16;
    h *= 0x7feb352du;
    h ^= h >> 15;
    h *= 0x846ca68bu;
    h ^= h >> 16;
    return h;
}

uint compact_scene_cell_key_u32(uint hash_value) {
    return (hash_value & 0x00ffffffu) + 1u;
}

float compact_scene_cell_key(uint hash_value) {
    return float(compact_scene_cell_key_u32(hash_value));
}

ivec3 scene_cell_for_reference_space_position(vec3 reference_space_point) {
    return ivec3(floor(reference_space_point / SCENE_PARTICLE_CELL_METERS));
}

uint scene_metadata_base(uint slot) {
    return slot * SCENE_META_WORDS_PER_SLOT;
}

uint frame_marker_u32() {
    return uint(clamp(frame_marker() + 0.5, 0.0, 4294967040.0));
}

uint scene_source_layer_mask() {
    return depth_source_layer_index() > 0.5 ? 0x2u : 0x1u;
}

uint scene_confidence_bucket(float confidence) {
    return uint(clamp(confidence * 255.0 + 0.5, 0.0, 255.0));
}

uint packed_scene_metadata_flags(float confidence) {
    return scene_source_layer_mask() | (scene_confidence_bucket(confidence) << 8);
}

bool try_lock_scene_metadata(uint meta_base, uint expected_state) {
    return atomicCompSwap(
        scene_meta.words[meta_base + SCENE_META_STATE],
        expected_state,
        SCENE_META_STATE_WRITING) == expected_state;
}

void publish_scene_metadata(uint meta_base, float confidence, uint state) {
    scene_meta.words[meta_base + SCENE_META_LAST_FRAME] = frame_marker_u32();
    scene_meta.words[meta_base + SCENE_META_FLAGS] = packed_scene_metadata_flags(confidence);
    memoryBarrierBuffer();
    atomicExchange(scene_meta.words[meta_base + SCENE_META_STATE], state);
}

void record_scene_claim_failed() {
    atomicAdd(depth_debug.values[RAW_DEBUG_HASH_CLAIM_FAILED_COUNT], 1u);
}

void retire_scene_cell(ivec3 cell) {
    uint capacity = max(uint(pc.params0.x), 1u);
    uint hash_value = hash_scene_cell(cell);
    uint cell_key_u = compact_scene_cell_key_u32(hash_value);
    float cell_key = float(cell_key_u);
    uint base_slot = hash_value % capacity;

    atomicAdd(depth_debug.values[RAW_DEBUG_FREE_SPACE_RETIRE_ATTEMPT_COUNT], 1u);
    for (uint probe = 0u; probe < SCENE_PARTICLE_PROBE_COUNT; probe++) {
        uint slot = (base_slot + probe) % capacity;
        uint base = slot * 4u;
        uint meta_base = scene_metadata_base(slot);
        uint observed_key = scene_meta.words[meta_base + SCENE_META_KEY];
        if (observed_key == 0u) {
            return;
        }
        if (observed_key == cell_key_u) {
            uint observed_state = scene_meta.words[meta_base + SCENE_META_STATE];
            if (observed_state != SCENE_META_STATE_ACTIVE
                || !try_lock_scene_metadata(meta_base, SCENE_META_STATE_ACTIVE)) {
                record_scene_claim_failed();
                return;
            }
            particles.rows[base + 1u].a = 0.0;
            particles.rows[base + 2u].w = 0.0;
            particles.rows[base + 3u] = vec4(cell_key, 0.0, frame_marker(), 2.0);
            publish_scene_metadata(meta_base, 0.0, SCENE_META_STATE_RETIRED);
            atomicAdd(depth_debug.values[RAW_DEBUG_FREE_SPACE_RETIRE_SUCCESS_COUNT], 1u);
            return;
        }
    }
}

void active_correct_visible_free_space(vec2 surface_uv, float observed_depth_meters) {
    float near_z = max(pc.params1.x, 0.001);
    float start_depth = near_z + SCENE_PARTICLE_ACTIVE_CORRECTION_STEP_METERS;
    float active_range = SCENE_PARTICLE_ACTIVE_CORRECTION_STEP_METERS
        * float(SCENE_PARTICLE_ACTIVE_CORRECTION_MAX_STEPS);
    float stop_depth = min(
        observed_depth_meters - SCENE_PARTICLE_ACTIVE_CORRECTION_SURFACE_KEEP_METERS,
        near_z + active_range);

    if (!(stop_depth > start_depth)) {
        return;
    }

    for (uint step_index = 0u; step_index < SCENE_PARTICLE_ACTIVE_CORRECTION_MAX_STEPS; step_index++) {
        float depth_meters = start_depth
            + (float(step_index) + 0.5) * SCENE_PARTICLE_ACTIVE_CORRECTION_STEP_METERS;
        if (depth_meters >= stop_depth) {
            return;
        }
        vec3 reference_space_point = reconstruct_reference_space_position(surface_uv, depth_meters);
        retire_scene_cell(scene_cell_for_reference_space_position(reference_space_point));
    }
}

void write_scene_particle(
    vec3 reference_space_point,
    float depth_meters,
    float confidence,
    vec2 surface_uv,
    vec2 depth_uv
) {
    uint capacity = max(uint(pc.params0.x), 1u);
    ivec3 cell = scene_cell_for_reference_space_position(reference_space_point);
    uint hash_value = hash_scene_cell(cell);
    uint cell_key_u = compact_scene_cell_key_u32(hash_value);
    float cell_key = float(cell_key_u);
    uint base_slot = hash_value % capacity;

    for (uint probe = 0u; probe < SCENE_PARTICLE_PROBE_COUNT; probe++) {
        uint slot = (base_slot + probe) % capacity;
        uint base = slot * 4u;
        uint meta_base = scene_metadata_base(slot);
        uint observed_key = atomicCompSwap(
            scene_meta.words[meta_base + SCENE_META_KEY],
            0u,
            cell_key_u);
        vec4 existing_position = particles.rows[base];
        vec4 existing_color = particles.rows[base + 1u];
        vec4 existing_sample = particles.rows[base + 2u];
        vec4 existing_state = particles.rows[base + 3u];
        uint observed_state = scene_meta.words[meta_base + SCENE_META_STATE];
        bool won_empty_key = observed_key == 0u;
        bool same_cell = observed_key == cell_key_u;
        float age_frames = max(frame_marker() - existing_state.z, 0.0);
        bool stale = age_frames > SCENE_PARTICLE_STALE_REPLACE_FRAMES;

        if (observed_key != 0u && observed_state == SCENE_META_STATE_ACTIVE) {
            atomicAdd(depth_debug.values[RAW_DEBUG_HASH_OCCUPANCY_ESTIMATE], 1u);
        }

        if (won_empty_key) {
            if (!try_lock_scene_metadata(meta_base, SCENE_META_STATE_EMPTY)) {
                atomicExchange(scene_meta.words[meta_base + SCENE_META_KEY], 0u);
                record_scene_claim_failed();
                return;
            }
            atomicAdd(depth_debug.values[RAW_DEBUG_HASH_INSERT_SUCCESS_COUNT], 1u);
            write_particle_slot(
                slot,
                reference_space_point,
                depth_meters,
                confidence,
                surface_uv,
                depth_uv,
                cell_key,
                active_scene_state_marker(probe));
            publish_scene_metadata(meta_base, confidence, SCENE_META_STATE_ACTIVE);
            return;
        }

        if (same_cell) {
            if (observed_state == SCENE_META_STATE_RETIRED) {
                if (!try_lock_scene_metadata(meta_base, SCENE_META_STATE_RETIRED)) {
                    record_scene_claim_failed();
                    return;
                }
                atomicAdd(depth_debug.values[RAW_DEBUG_HASH_INSERT_SUCCESS_COUNT], 1u);
                write_particle_slot(
                    slot,
                    reference_space_point,
                    depth_meters,
                    confidence,
                    surface_uv,
                    depth_uv,
                    cell_key,
                    active_scene_state_marker(probe));
                publish_scene_metadata(meta_base, confidence, SCENE_META_STATE_ACTIVE);
                return;
            }
            if (observed_state != SCENE_META_STATE_ACTIVE
                || !try_lock_scene_metadata(meta_base, SCENE_META_STATE_ACTIVE)) {
                record_scene_claim_failed();
                return;
            }
            if (stale) {
                atomicAdd(depth_debug.values[RAW_DEBUG_HASH_STALE_REPLACE_COUNT], 1u);
            } else {
                atomicAdd(depth_debug.values[RAW_DEBUG_HASH_MERGE_COUNT], 1u);
            }
            float merge_weight = !stale
                ? SCENE_PARTICLE_MERGE_WEIGHT * clamp(confidence, 0.0, 1.0)
                : 1.0;
            vec3 merged_position = !stale
                ? mix(existing_position.xyz, reference_space_point, merge_weight)
                : reference_space_point;
            float merged_depth = !stale
                ? mix(existing_sample.z, depth_meters, merge_weight)
                : depth_meters;
            float merged_confidence = !stale
                ? clamp(
                    max(
                        existing_state.y * 0.995,
                        mix(existing_state.y, confidence, 0.22) + confidence * 0.035),
                    0.0,
                    1.0)
                : confidence;
            write_particle_slot(
                slot,
                merged_position,
                merged_depth,
                merged_confidence,
                surface_uv,
                depth_uv,
                cell_key,
                active_scene_state_marker(probe));
            publish_scene_metadata(meta_base, merged_confidence, SCENE_META_STATE_ACTIVE);
            return;
        }

        if (observed_state == SCENE_META_STATE_RETIRED || stale) {
            uint expected_state = observed_state == SCENE_META_STATE_RETIRED
                ? SCENE_META_STATE_RETIRED
                : SCENE_META_STATE_ACTIVE;
            if (!try_lock_scene_metadata(meta_base, expected_state)) {
                record_scene_claim_failed();
                return;
            }
            if (scene_meta.words[meta_base + SCENE_META_KEY] != observed_key) {
                publish_scene_metadata(meta_base, 0.0, expected_state);
                record_scene_claim_failed();
                return;
            }
            atomicExchange(scene_meta.words[meta_base + SCENE_META_KEY], cell_key_u);
            atomicAdd(depth_debug.values[RAW_DEBUG_HASH_STALE_REPLACE_COUNT], 1u);
            write_particle_slot(
                slot,
                reference_space_point,
                depth_meters,
                confidence,
                surface_uv,
                depth_uv,
                cell_key,
                active_scene_state_marker(probe));
            publish_scene_metadata(meta_base, confidence, SCENE_META_STATE_ACTIVE);
            return;
        }
        atomicAdd(depth_debug.values[RAW_DEBUG_HASH_WRITE_CONFLICT_COUNT], 1u);
    }
    atomicAdd(depth_debug.values[RAW_DEBUG_HASH_PROBE_EXHAUSTED_COUNT], 1u);
}

uint meters_to_debug_mm(float meters) {
    return uint(clamp(meters * 1000.0 + 0.5, 0.0, 4294967295.0));
}

uint raw_to_debug_d16(float raw_depth) {
    return uint(clamp(raw_depth * 65535.0 + 0.5, 0.0, 65535.0));
}

void observe_neighbor_depth(
    vec2 depth_uv,
    float depth_meters,
    inout uint valid_neighbor_count,
    inout float max_discontinuity
) {
    float raw_neighbor_depth = sample_raw_depth(depth_uv);
    if (!raw_depth_is_valid(raw_neighbor_depth)) {
        return;
    }
    float neighbor_depth_meters = raw_depth_to_meters(raw_neighbor_depth);
    valid_neighbor_count++;
    max_discontinuity = max(max_discontinuity, abs(depth_meters - neighbor_depth_meters));
}

float confidence_for_depth_uv(vec2 depth_uv, float depth_meters, ivec2 depth_size) {
    vec2 sample_step = 1.0 / max(vec2(depth_size), vec2(1.0));
    uint valid_neighbor_count = 0u;
    float max_discontinuity = 0.0;
    observe_neighbor_depth(
        depth_uv + vec2(sample_step.x, 0.0),
        depth_meters,
        valid_neighbor_count,
        max_discontinuity);
    observe_neighbor_depth(
        depth_uv - vec2(sample_step.x, 0.0),
        depth_meters,
        valid_neighbor_count,
        max_discontinuity);
    observe_neighbor_depth(
        depth_uv + vec2(0.0, sample_step.y),
        depth_meters,
        valid_neighbor_count,
        max_discontinuity);
    observe_neighbor_depth(
        depth_uv - vec2(0.0, sample_step.y),
        depth_meters,
        valid_neighbor_count,
        max_discontinuity);

    if (valid_neighbor_count < 2u) {
        return 0.0;
    }

    float support_confidence = valid_neighbor_count >= 3u ? 1.0 : 0.82;
    float edge_confidence = 1.0 - smoothstep(0.28, 0.56, max_discontinuity);
    return clamp(edge_confidence * support_confidence, 0.0, 1.0);
}

bool surface_support_neighbor_is_coherent(
    ivec2 neighbor_pixel,
    float depth_meters,
    ivec2 depth_size
) {
    ivec2 pixel = clamp(neighbor_pixel, ivec2(0), depth_size - ivec2(1));
    vec2 surface_uv = (vec2(pixel) + vec2(0.5)) / max(vec2(depth_size), vec2(1.0));
    vec2 depth_uv = clamp(
        apply_depth_texture_transform(surface_uv, int(floor(pc.params0.z + 0.5))),
        vec2(0.0),
        vec2(1.0));
    float raw_neighbor_depth = sample_raw_depth(depth_uv);
    if (!raw_depth_is_valid(raw_neighbor_depth)) {
        return false;
    }
    float neighbor_depth_meters = raw_depth_to_meters(raw_neighbor_depth);
    bool neighbor_in_range = neighbor_depth_meters >= max(pc.params1.x, 0.001)
        && neighbor_depth_meters <= max(pc.params1.y, pc.params1.x + 0.01);
    float tolerance = max(
        SCENE_PARTICLE_SURFACE_SUPPORT_MIN_DEPTH_TOLERANCE_METERS,
        depth_meters * SCENE_PARTICLE_SURFACE_SUPPORT_DEPTH_TOLERANCE_SCALE);
    return neighbor_in_range && abs(depth_meters - neighbor_depth_meters) <= tolerance;
}

uint surface_support_neighbor_count(
    ivec2 center_pixel,
    float depth_meters,
    ivec2 depth_size,
    uint sample_stride
) {
    uint support_count = 0u;
    int radius = int(surface_support_radius_cells());
    int stride = int(max(sample_stride, 1u));
    for (int y = -8; y <= 8; y++) {
        for (int x = -8; x <= 8; x++) {
            if ((x == 0 && y == 0) || abs(x) > radius || abs(y) > radius) {
                continue;
            }
            if (surface_support_neighbor_is_coherent(
                center_pixel + ivec2(x * stride, y * stride),
                depth_meters,
                depth_size)) {
                support_count++;
            }
        }
    }
    return support_count;
}

bool surface_support_passes(
    ivec2 center_pixel,
    float depth_meters,
    ivec2 depth_size,
    uint sample_stride
) {
    if (!surface_support_enforced_requested()) {
        return true;
    }
    uint min_neighbors = surface_support_min_neighbor_count();
    if (min_neighbors == 0u) {
        return true;
    }
    return surface_support_neighbor_count(center_pixel, depth_meters, depth_size, sample_stride)
        >= min_neighbors;
}

void sort_raw_prefix(inout float raw_values[9], int count) {
    for (int outer_index = 0; outer_index < 8; outer_index++) {
        for (int inner_index = outer_index + 1; inner_index < 9; inner_index++) {
            if (outer_index < count && inner_index < count && raw_values[inner_index] < raw_values[outer_index]) {
                float swap_value = raw_values[outer_index];
                raw_values[outer_index] = raw_values[inner_index];
                raw_values[inner_index] = swap_value;
            }
        }
    }
}

void write_center_raw_debug_window(ivec2 depth_size) {
    if (gl_GlobalInvocationID.x != 0u || gl_GlobalInvocationID.y != 0u || depth_size.x <= 0 || depth_size.y <= 0) {
        return;
    }

    ivec2 center_pixel = depth_size / 2;
    float raw_values[9];
    int valid_count = 0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            ivec2 pixel = clamp(center_pixel + ivec2(x, y), ivec2(0), depth_size - ivec2(1));
            vec2 surface_uv = (vec2(pixel) + vec2(0.5)) / max(vec2(depth_size), vec2(1.0));
            vec2 depth_uv = clamp(
                apply_depth_texture_transform(surface_uv, int(floor(pc.params0.z + 0.5))),
                vec2(0.0),
                vec2(1.0));
            float raw_depth = sample_raw_depth(depth_uv);
            if (raw_depth_is_valid(raw_depth)) {
                raw_values[valid_count] = raw_depth;
                valid_count++;
            }
        }
    }

    ivec2 center_clamped = clamp(center_pixel, ivec2(0), depth_size - ivec2(1));
    vec2 center_surface_uv = (vec2(center_clamped) + vec2(0.5)) / max(vec2(depth_size), vec2(1.0));
    vec2 center_depth_uv = clamp(
        apply_depth_texture_transform(center_surface_uv, int(floor(pc.params0.z + 0.5))),
        vec2(0.0),
        vec2(1.0));
    float center_raw_depth = sample_raw_depth(center_depth_uv);
    if (raw_depth_is_valid(center_raw_depth)) {
        float center_depth_meters = raw_depth_to_meters(center_raw_depth);
        float center_confidence = confidence_for_depth_uv(center_depth_uv, center_depth_meters, depth_size);
        depth_debug.values[RAW_DEBUG_CENTER_D16] = raw_to_debug_d16(center_raw_depth);
        depth_debug.values[RAW_DEBUG_CENTER_RECONSTRUCTED_MM] = meters_to_debug_mm(center_depth_meters);
        depth_debug.values[RAW_DEBUG_CENTER_CONFIDENCE_MILLI] = uint(clamp(center_confidence * 1000.0 + 0.5, 0.0, 1000.0));
    }

    if (valid_count > 0) {
        sort_raw_prefix(raw_values, valid_count);
        depth_debug.values[RAW_DEBUG_CENTER_MEDIAN_D16] = raw_to_debug_d16(raw_values[valid_count / 2]);
        depth_debug.values[RAW_DEBUG_CENTER_WINDOW_VALID_COUNT] = uint(valid_count);
    }
}

void accumulate_raw_debug_stats(bool raw_valid, bool in_range, bool confidence_valid, float depth_meters) {
    if (confidence_valid) {
        uint depth_mm = meters_to_debug_mm(depth_meters);
        atomicAdd(depth_debug.values[RAW_DEBUG_VALID_COUNT], 1u);
        atomicMax(depth_debug.values[RAW_DEBUG_MIN_VALID_INVERSE_MM], 0xffffffffu - depth_mm);
        atomicMax(depth_debug.values[RAW_DEBUG_MAX_VALID_MM], depth_mm);
        return;
    }
    if (raw_valid && in_range) {
        atomicAdd(depth_debug.values[RAW_DEBUG_CONFIDENCE_REJECTED_COUNT], 1u);
    } else {
        atomicAdd(depth_debug.values[RAW_DEBUG_INVALID_COUNT], 1u);
    }
}

void main() {
    ivec2 depth_size = textureSize(u_environment_depth, 0).xy;
    write_center_raw_debug_window(depth_size);
    uint particle_count = uint(max(pc.params0.x, 0.0));
    uint sample_stride = max(uint(pc.params1.w), 1u);
    uvec2 grid_size = max(uvec2(depth_size) / uvec2(sample_stride), uvec2(1u));
    uint gx = gl_GlobalInvocationID.x;
    uint gy = gl_GlobalInvocationID.y;
    if (gx >= grid_size.x || gy >= grid_size.y) {
        return;
    }

    uint sample_index = gy * grid_size.x + gx;
    bool scene_map = scene_particle_map_requested();
    if (!scene_map && sample_index >= particle_count) {
        return;
    }

    ivec2 pixel = min(
        ivec2(gx, gy) * int(sample_stride) + ivec2(int(sample_stride / 2u)),
        depth_size - ivec2(1));
    vec2 surface_uv = (vec2(pixel) + vec2(0.5)) / max(vec2(depth_size), vec2(1.0));
    vec2 depth_uv = clamp(
        apply_depth_texture_transform(surface_uv, int(floor(pc.params0.z + 0.5))),
        vec2(0.0),
        vec2(1.0));

    float raw_depth = sample_raw_depth(depth_uv);
    bool raw_valid = raw_depth_is_valid(raw_depth);
    float depth_meters = raw_valid ? raw_depth_to_meters(raw_depth) : pc.params1.y + 1.0;
    float confidence = confidence_for_depth_uv(depth_uv, depth_meters, depth_size);
    float confidence_threshold = scene_map ? SCENE_PARTICLE_CONFIDENCE_THRESHOLD : 0.52;
    bool in_range = raw_valid
        && depth_meters >= max(pc.params1.x, 0.001)
        && depth_meters <= max(pc.params1.y, pc.params1.x + 0.01);
    bool valid = in_range && confidence >= confidence_threshold;
    accumulate_raw_debug_stats(raw_valid, in_range, valid, depth_meters);

    if (!valid) {
        if (!scene_map) {
            write_retained_invalid(sample_index);
        }
        return;
    }

    if (scene_map && surface_support_enforced_requested()) {
        if (!surface_support_passes(pixel, depth_meters, depth_size, sample_stride)) {
            atomicAdd(depth_debug.values[RAW_DEBUG_SURFACE_REJECTED_ISOLATED_CELLS], 1u);
            return;
        }
        atomicAdd(depth_debug.values[RAW_DEBUG_SURFACE_SUPPORTED_CELLS], 1u);
    }

    vec3 reference_space_point = reconstruct_reference_space_position(surface_uv, depth_meters);
    if (scene_map) {
        if (confidence >= SCENE_PARTICLE_ACTIVE_CORRECTION_CONFIDENCE) {
            active_correct_visible_free_space(surface_uv, depth_meters);
        } else {
            atomicAdd(depth_debug.values[RAW_DEBUG_FREE_SPACE_CONFIDENCE_SKIPPED_COUNT], 1u);
        }
        write_scene_particle(reference_space_point, depth_meters, confidence, surface_uv, depth_uv);
        return;
    }

    write_particle_slot(
        sample_index,
        reference_space_point,
        depth_meters,
        confidence,
        surface_uv,
        depth_uv,
        float(sample_index),
        0.0);
}

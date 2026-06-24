#version 450

layout(location = 0) in vec2 v_mask_uv;
layout(location = 1) in vec4 v_color;
layout(location = 2) in vec4 v_render_params;
layout(location = 3) in vec4 v_color_params;
layout(location = 0) out vec4 out_color;

#ifndef PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE
#define PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE 0
#endif

#ifndef PRIVATE_PARTICLE_MASK_TEXTURE_LAYERS
#define PRIVATE_PARTICLE_MASK_TEXTURE_LAYERS 1
#endif

#ifndef PRIVATE_PARTICLE_MASK_TEXTURE_ATLAS_COLUMNS
#define PRIVATE_PARTICLE_MASK_TEXTURE_ATLAS_COLUMNS 1
#endif

#ifndef PRIVATE_PARTICLE_MASK_TEXTURE_ATLAS_ROWS
#define PRIVATE_PARTICLE_MASK_TEXTURE_ATLAS_ROWS 1
#endif

#define PRIVATE_PARTICLE_MASK_MODE_PROCEDURAL 0
#define PRIVATE_PARTICLE_MASK_MODE_ARRAY_NEAREST 1
#define PRIVATE_PARTICLE_MASK_MODE_ARRAY_BLEND 2
#define PRIVATE_PARTICLE_MASK_MODE_ATLAS_NEAREST 3
#define PRIVATE_PARTICLE_MASK_MODE_ATLAS_BLEND 4

#if PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE == PRIVATE_PARTICLE_MASK_MODE_ARRAY_NEAREST \
    || PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE == PRIVATE_PARTICLE_MASK_MODE_ARRAY_BLEND
layout(set = 0, binding = 6) uniform sampler2DArray u_mask_array;
#elif PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE == PRIVATE_PARTICLE_MASK_MODE_ATLAS_NEAREST \
    || PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE == PRIVATE_PARTICLE_MASK_MODE_ATLAS_BLEND
layout(set = 0, binding = 6) uniform sampler2D u_mask_atlas;
#endif

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

#ifndef PRIVATE_PARTICLE_MASK_ALPHA_CUTOFF
#define PRIVATE_PARTICLE_MASK_ALPHA_CUTOFF 0.001
#endif

#if PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE == PRIVATE_PARTICLE_MASK_MODE_PROCEDURAL
const float TAU = 6.28318530717958647692;
const float AKD_RING_EDGE_WIDTH = 0.015;
const float AKD_RING_OUTER_FEATHER = 0.06;
const float AKD_RING_RADIUS = 0.32;
const float AKD_RING_THICKNESS = 0.03;
const float AKD_RING_DUAL_OFFSET_RADIANS = 3.14159265358979323846;

float seg_dist(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a;
    vec2 ba = b - a;
    float h = clamp(dot(pa, ba) / max(dot(ba, ba), 0.000001), 0.0, 1.0);
    return length(pa - ba * h);
}

float morph_factor(float t) {
    t = min(clamp(t, 0.0, 1.0), 0.99902344);
    float t4 = t * 4.0;
    float seg = floor(t4);
    float frac_part = t4 - seg;
    float slope = mix(1.0, -1.0, step(2.0, seg));
    float offset = mix(
        mix(0.0, 1.0, step(1.0, seg)),
        mix(2.0, 1.0, step(3.0, seg)),
        step(2.0, seg));
    return offset + slope * frac_part;
}

vec2 morphed_arc_point(float a0, float a1, float s, float m, float radius) {
    float th = a0 + s * (a1 - a0);
    vec2 circle_point = radius * vec2(cos(th), sin(th));
    vec2 a_point = radius * vec2(cos(a0), sin(a0));
    vec2 b_point = radius * vec2(cos(a1), sin(a1));
    vec2 chord_point = mix(a_point, b_point, s);
    return circle_point + m * (chord_point - circle_point);
}

vec2 rotate_around_center(vec2 p, float angle) {
    vec2 center = vec2(0.5);
    vec2 local = p - center;
    float s = sin(angle);
    float c = cos(angle);
    return center + vec2(local.x * c - local.y * s, local.x * s + local.y * c);
}

float morphed_ring_dist_single(vec2 p, float phase01) {
    vec2 center = vec2(0.5);
    float m = morph_factor(phase01);
    float safe_thickness = min(AKD_RING_THICKNESS, AKD_RING_RADIUS * 0.99);
    float mid_radius = max(AKD_RING_RADIUS - 0.5 * safe_thickness, 0.0001);
    float d_min = 999.0;
    for (int arc = 0; arc < 3; ++arc) {
        float a0 = float(arc) * (TAU / 3.0);
        float a1 = float(arc + 1) * (TAU / 3.0);
        vec2 prev = center + morphed_arc_point(a0, a1, 0.0, m, mid_radius);
        for (int i = 1; i <= 8; ++i) {
            float s = float(i) / 8.0;
            vec2 cur = center + morphed_arc_point(a0, a1, s, m, mid_radius);
            d_min = min(d_min, seg_dist(p, prev, cur));
            prev = cur;
        }
    }
    return d_min;
}

float morphed_ring_dist(vec2 p, float phase01) {
    float full_offset = AKD_RING_DUAL_OFFSET_RADIANS * abs(phase01 * 2.0 - 1.0);
    float half_offset = 0.5 * full_offset;
    float d_a = morphed_ring_dist_single(rotate_around_center(p, -half_offset), phase01);
    float d_b = morphed_ring_dist_single(rotate_around_center(p, half_offset), phase01);
    return min(d_a, d_b);
}

float procedural_morphed_ring_alpha(vec2 uv, float frame01) {
    float d = morphed_ring_dist(uv, frame01);
    float aa = max(fwidth(d), 0.0001);
    float core = 1.0 - smoothstep(AKD_RING_EDGE_WIDTH, AKD_RING_EDGE_WIDTH + aa, d);
    float feather = 1.0 - smoothstep(
        AKD_RING_EDGE_WIDTH + aa,
        AKD_RING_EDGE_WIDTH + aa + AKD_RING_OUTER_FEATHER,
        d);
    return max(core, feather);
}
#endif

#if PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE == PRIVATE_PARTICLE_MASK_MODE_ARRAY_NEAREST \
    || PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE == PRIVATE_PARTICLE_MASK_MODE_ARRAY_BLEND
float texture_array_alpha_nearest(vec2 uv, float frame01) {
    int layers = max(textureSize(u_mask_array, 0).z, 1);
    float frame = clamp(frame01, 0.0, 0.99902344) * float(layers - 1);
    float layer = floor(frame + 0.5);
    return texture(u_mask_array, vec3(uv, layer)).r;
}

float texture_array_alpha_blend(vec2 uv, float frame01) {
    int layers = max(textureSize(u_mask_array, 0).z, 1);
    float frame = clamp(frame01, 0.0, 0.99902344) * float(layers - 1);
    float layer0 = floor(frame);
    float layer1 = min(layer0 + 1.0, float(layers - 1));
    float mix01 = frame - layer0;
    float alpha0 = texture(u_mask_array, vec3(uv, layer0)).r;
    float alpha1 = texture(u_mask_array, vec3(uv, layer1)).r;
    return mix(alpha0, alpha1, mix01);
}
#endif

#if PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE == PRIVATE_PARTICLE_MASK_MODE_ATLAS_NEAREST \
    || PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE == PRIVATE_PARTICLE_MASK_MODE_ATLAS_BLEND
vec2 texture_atlas_uv(vec2 uv, float layer) {
    int layers = max(PRIVATE_PARTICLE_MASK_TEXTURE_LAYERS, 1);
    int columns = max(PRIVATE_PARTICLE_MASK_TEXTURE_ATLAS_COLUMNS, 1);
    int rows = max(PRIVATE_PARTICLE_MASK_TEXTURE_ATLAS_ROWS, 1);
    int layer_index = clamp(int(layer), 0, layers - 1);
    int column = layer_index % columns;
    int row = min(layer_index / columns, rows - 1);
    vec2 atlas_size = vec2(textureSize(u_mask_atlas, 0));
    vec2 cell_count = vec2(float(columns), float(rows));
    vec2 texel_guard = (vec2(0.5) / atlas_size) * cell_count;
    vec2 local_uv = clamp(uv, texel_guard, vec2(1.0) - texel_guard);
    return (vec2(float(column), float(row)) + local_uv) / cell_count;
}

float texture_atlas_alpha_nearest(vec2 uv, float frame01) {
    int layers = max(PRIVATE_PARTICLE_MASK_TEXTURE_LAYERS, 1);
    float frame = clamp(frame01, 0.0, 0.99902344) * float(layers - 1);
    float layer = floor(frame + 0.5);
    return texture(u_mask_atlas, texture_atlas_uv(uv, layer)).r;
}

float texture_atlas_alpha_blend(vec2 uv, float frame01) {
    int layers = max(PRIVATE_PARTICLE_MASK_TEXTURE_LAYERS, 1);
    float frame = clamp(frame01, 0.0, 0.99902344) * float(layers - 1);
    float layer0 = floor(frame);
    float layer1 = min(layer0 + 1.0, float(layers - 1));
    float mix01 = frame - layer0;
    float alpha0 = texture(u_mask_atlas, texture_atlas_uv(uv, layer0)).r;
    float alpha1 = texture(u_mask_atlas, texture_atlas_uv(uv, layer1)).r;
    return mix(alpha0, alpha1, mix01);
}
#endif

void main() {
    float frame01 = clamp(v_render_params.y, 0.0, 0.99902344);
    uint packed_mode = uint(pc.params0.z + 0.5);
    uint mask_discard_mode = (packed_mode / 1000000u) % 10u;
#if PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE == PRIVATE_PARTICLE_MASK_MODE_ARRAY_NEAREST
    float mask = texture_array_alpha_nearest(v_mask_uv, frame01);
#elif PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE == PRIVATE_PARTICLE_MASK_MODE_ARRAY_BLEND
    float mask = texture_array_alpha_blend(v_mask_uv, frame01);
#elif PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE == PRIVATE_PARTICLE_MASK_MODE_ATLAS_NEAREST
    float mask = texture_atlas_alpha_nearest(v_mask_uv, frame01);
#elif PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE == PRIVATE_PARTICLE_MASK_MODE_ATLAS_BLEND
    float mask = texture_atlas_alpha_blend(v_mask_uv, frame01);
#else
    float mask = procedural_morphed_ring_alpha(v_mask_uv, frame01);
#endif
    bool visible_mask_pixel = mask >= PRIVATE_PARTICLE_MASK_ALPHA_CUTOFF;
    if (!visible_mask_pixel && mask_discard_mode == 0u) {
        discard;
    }
    if (!visible_mask_pixel) {
        out_color = vec4(0.0);
        return;
    }
    float opacity = clamp(pc.transparency_params.x, 0.0, 4.0);
    float output_alpha_scale = clamp(pc.transparency_params.y, 0.0, 4.0);
    float rgb_alpha_coupling = clamp(pc.transparency_params.w, 0.0, 1.0);

    float coverage_alpha = clamp(mask * v_color.a * opacity, 0.0, 1.0);
    vec3 base_rgb = clamp(v_color.rgb, vec3(0.0), vec3(1.0))
        * clamp(v_color_params.x, 0.0, 1.0);
    vec3 rgb = base_rgb * mix(1.0, coverage_alpha, rgb_alpha_coupling);
    out_color = vec4(rgb, clamp(coverage_alpha * output_alpha_scale, 0.0, 1.0));
}

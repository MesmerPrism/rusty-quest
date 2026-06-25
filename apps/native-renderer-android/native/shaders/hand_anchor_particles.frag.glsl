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

#ifndef PRIVATE_PARTICLE_MASK_ALPHA_CUTOFF
#define PRIVATE_PARTICLE_MASK_ALPHA_CUTOFF 0.001
#endif

#define PRIVATE_PARTICLE_MASK_MODE_PROCEDURAL 0
#define PRIVATE_PARTICLE_MASK_MODE_ARRAY_NEAREST 1
#define PRIVATE_PARTICLE_MASK_MODE_ARRAY_BLEND 2
#define PRIVATE_PARTICLE_MASK_MODE_ATLAS_NEAREST 3
#define PRIVATE_PARTICLE_MASK_MODE_ATLAS_BLEND 4

#if PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE == PRIVATE_PARTICLE_MASK_MODE_ARRAY_NEAREST \
    || PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE == PRIVATE_PARTICLE_MASK_MODE_ARRAY_BLEND
layout(set = 0, binding = 4) uniform sampler2DArray u_mask_array;
#elif PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE == PRIVATE_PARTICLE_MASK_MODE_ATLAS_NEAREST \
    || PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE == PRIVATE_PARTICLE_MASK_MODE_ATLAS_BLEND
layout(set = 0, binding = 4) uniform sampler2D u_mask_atlas;
#endif

float procedural_feather_dot_alpha(vec2 uv) {
    vec2 centered = uv * 2.0 - vec2(1.0);
    float radius = length(centered);
    return 1.0 - smoothstep(0.72, 1.0, radius);
}

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
    float frame01 = 0.0;
#if PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE == PRIVATE_PARTICLE_MASK_MODE_ARRAY_NEAREST
    float mask = texture_array_alpha_nearest(v_mask_uv, frame01);
#elif PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE == PRIVATE_PARTICLE_MASK_MODE_ARRAY_BLEND
    float mask = texture_array_alpha_blend(v_mask_uv, frame01);
#elif PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE == PRIVATE_PARTICLE_MASK_MODE_ATLAS_NEAREST
    float mask = texture_atlas_alpha_nearest(v_mask_uv, frame01);
#elif PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE == PRIVATE_PARTICLE_MASK_MODE_ATLAS_BLEND
    float mask = texture_atlas_alpha_blend(v_mask_uv, frame01);
#else
    float mask = procedural_feather_dot_alpha(v_mask_uv);
#endif
    if (mask < PRIVATE_PARTICLE_MASK_ALPHA_CUTOFF) {
        discard;
    }

    float premultiply_rgb = v_render_params.x;
    float composition_mode = v_render_params.y;
    float depth_suppression_strength = max(v_render_params.z, 0.0);
    float view_depth_m = max(v_render_params.w, 0.0);
    float depth01 = clamp((view_depth_m - 0.08) / 0.55, 0.0, 1.0);
    float depth_weight = composition_mode > 0.5
        ? exp2(-depth_suppression_strength * depth01)
        : 1.0;

    float alpha = clamp(mask * v_color.a * depth_weight, 0.0, 1.0);
    vec3 rgb = clamp(v_color.rgb, vec3(0.0), vec3(1.0))
        * clamp(v_color_params.x, 0.0, 1.0);
    if (premultiply_rgb > 0.5) {
        rgb *= alpha;
    }
    out_color = vec4(rgb, alpha);
}

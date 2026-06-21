#version 450

layout(set = 0, binding = 0) uniform sampler2D u_guide;
layout(set = 1, binding = 0) uniform sampler2D u_video_projection;

layout(push_constant) uniform GuideVideoProjectionPush {
    vec4 target_rect;
    vec4 params;
    vec4 stretch0;
    vec4 stretch1;
    vec4 alpha;
    vec4 video_target_rect;
    vec4 video_source_uv_rect;
    vec4 video_params;
} pc;

layout(location = 0) in vec2 v_uv;
layout(location = 0) out vec4 out_color;

float uv_valid(vec2 coord) {
    return step(0.0, coord.x) *
        step(coord.x, 1.0) *
        step(0.0, coord.y) *
        step(coord.y, 1.0);
}

float target_footprint_signed_distance_uv(vec2 local_uv) {
    vec2 q = abs(local_uv - vec2(0.5)) - vec2(0.5);
    float outside = length(max(q, vec2(0.0)));
    float inside = min(max(q.x, q.y), 0.0);
    return outside + inside;
}

float peripheral_stretch_blend_weight(float signed_distance_uv) {
    float blend_mode = floor(pc.stretch1.z + 0.5);
    float inner_blend = clamp(pc.stretch1.x, 0.0, 0.25);
    float blend_curve = clamp(pc.stretch1.y, 0.25, 6.0);
    if (blend_mode < 0.5) {
        return step(0.0, signed_distance_uv);
    }
    if (signed_distance_uv >= 0.0) {
        return 1.0;
    }
    if (inner_blend <= 0.0001) {
        return 0.0;
    }
    float t = smoothstep(-inner_blend, 0.0, signed_distance_uv);
    return pow(t, blend_curve);
}

vec3 linear_to_srgb(vec3 value) {
    return pow(max(value, vec3(0.0)), vec3(1.0 / 2.2));
}

vec3 srgb_to_linear(vec3 value) {
    return pow(clamp(value, vec3(0.0), vec3(1.0)), vec3(2.2));
}

vec3 luma_matched_camera_rgb(vec3 camera_rgb, vec3 video_rgb, float edge_weight) {
    const vec3 luma_weights = vec3(0.2126, 0.7152, 0.0722);
    float camera_luma = max(dot(camera_rgb, luma_weights), 0.001);
    float video_luma = max(dot(video_rgb, luma_weights), 0.001);
    float gain = clamp(video_luma / camera_luma, 0.55, 1.80);
    return mix(camera_rgb, clamp(camera_rgb * gain, vec3(0.0), vec3(1.0)), edge_weight * 0.75);
}

float luma(vec3 rgb) {
    return clamp(dot(rgb, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
}

vec3 guide_sample_rgb(vec2 local_uv) {
    return clamp(
        texture(u_guide, clamp(local_uv, vec2(0.0001), vec2(0.9999))).rgb,
        vec3(0.0),
        vec3(1.0)
    );
}

vec2 video_source_uv_from_screen_uv(vec2 screen_uv) {
    vec2 video_target_size = max(pc.video_target_rect.zw, vec2(0.0001));
    vec2 video_local_uv = (screen_uv - pc.video_target_rect.xy) / video_target_size;
    vec2 source_position_offset_uv = pc.video_params.xy;
    float flip_y = step(0.5, pc.alpha.w);
    vec2 positioned_local_uv = clamp(
        video_local_uv - source_position_offset_uv,
        vec2(0.0),
        vec2(1.0)
    );
    vec2 oriented_uv = vec2(
        positioned_local_uv.x,
        mix(positioned_local_uv.y, 1.0 - positioned_local_uv.y, flip_y)
    );
    vec2 source_uv = pc.video_source_uv_rect.xy + oriented_uv * pc.video_source_uv_rect.zw;
    vec2 source_texel = 0.5 / max(vec2(textureSize(u_video_projection, 0)), vec2(1.0));
    vec2 source_min = pc.video_source_uv_rect.xy + source_texel;
    vec2 source_max = pc.video_source_uv_rect.xy + pc.video_source_uv_rect.zw - source_texel;
    return clamp(source_uv, source_min, source_max);
}

vec3 video_sample_rgb_at_screen_uv(vec2 screen_uv) {
    return clamp(
        texture(u_video_projection, video_source_uv_from_screen_uv(screen_uv)).rgb,
        vec3(0.0),
        vec3(1.0)
    );
}

vec3 video_sample_rgb() {
    return video_sample_rgb_at_screen_uv(v_uv);
}

float transition_band_weight(float camera_weight) {
    return clamp(1.0 - abs(camera_weight * 2.0 - 1.0), 0.0, 1.0);
}

vec3 chroma_luma_split_rgb(vec3 camera_rgb, vec3 video_rgb, float camera_weight, float edge_weight) {
    float camera_luma = luma(camera_rgb);
    float video_luma = luma(video_rgb);
    vec3 camera_chroma = camera_rgb - vec3(camera_luma);
    vec3 video_chroma = video_rgb - vec3(video_luma);
    float chroma_weight = clamp(camera_weight * (1.0 - 0.45 * edge_weight), 0.0, 1.0);
    return clamp(
        vec3(mix(video_luma, camera_luma, camera_weight)) +
            mix(video_chroma, camera_chroma, chroma_weight),
        vec3(0.0),
        vec3(1.0)
    );
}

vec3 overlay_rgb(vec3 base_rgb, vec3 blend_rgb) {
    vec3 low = 2.0 * base_rgb * blend_rgb;
    vec3 high = 1.0 - 2.0 * (1.0 - base_rgb) * (1.0 - blend_rgb);
    return mix(low, high, step(vec3(0.5), base_rgb));
}

vec3 screen_rgb(vec3 base_rgb, vec3 blend_rgb) {
    return 1.0 - (1.0 - base_rgb) * (1.0 - blend_rgb);
}

vec3 soft_light_rgb(vec3 base_rgb, vec3 blend_rgb) {
    vec3 low = base_rgb - (1.0 - 2.0 * blend_rgb) * base_rgb * (1.0 - base_rgb);
    vec3 high = base_rgb + (2.0 * blend_rgb - 1.0) * (sqrt(max(base_rgb, vec3(0.0))) - base_rgb);
    return mix(low, high, step(vec3(0.5), blend_rgb));
}

vec3 band_limited_mode_rgb(vec3 crossfade_rgb, vec3 mode_rgb, float camera_weight) {
    return mix(crossfade_rgb, clamp(mode_rgb, vec3(0.0), vec3(1.0)), transition_band_weight(camera_weight));
}

vec3 gradient_aware_rgb(vec3 camera_rgb, vec3 video_rgb, float camera_weight) {
    float band = transition_band_weight(camera_weight);
    float camera_gradient = length(dFdx(camera_rgb)) + length(dFdy(camera_rgb));
    float video_gradient = length(dFdx(video_rgb)) + length(dFdy(video_rgb));
    float sharp_bias = clamp((camera_gradient - video_gradient) * 7.5, -0.22, 0.22);
    float adjusted_weight = clamp(camera_weight + sharp_bias * band, 0.0, 1.0);
    return mix(video_rgb, camera_rgb, adjusted_weight);
}

vec3 guide_low_pass_rgb(vec2 local_uv) {
    vec2 texel = 1.35 / max(vec2(textureSize(u_guide, 0)), vec2(1.0));
    return guide_sample_rgb(local_uv) * 0.40 +
        guide_sample_rgb(local_uv + vec2(texel.x, 0.0)) * 0.15 +
        guide_sample_rgb(local_uv - vec2(texel.x, 0.0)) * 0.15 +
        guide_sample_rgb(local_uv + vec2(0.0, texel.y)) * 0.15 +
        guide_sample_rgb(local_uv - vec2(0.0, texel.y)) * 0.15;
}

vec3 video_low_pass_rgb(vec2 screen_uv) {
    vec2 texel = 1.35 / max(vec2(textureSize(u_video_projection, 0)), vec2(1.0));
    vec2 source_uv = video_source_uv_from_screen_uv(screen_uv);
    vec2 source_min = pc.video_source_uv_rect.xy + texel;
    vec2 source_max = pc.video_source_uv_rect.xy + pc.video_source_uv_rect.zw - texel;
    vec3 center = texture(u_video_projection, clamp(source_uv, source_min, source_max)).rgb;
    vec3 right = texture(u_video_projection, clamp(source_uv + vec2(texel.x, 0.0), source_min, source_max)).rgb;
    vec3 left = texture(u_video_projection, clamp(source_uv - vec2(texel.x, 0.0), source_min, source_max)).rgb;
    vec3 down = texture(u_video_projection, clamp(source_uv + vec2(0.0, texel.y), source_min, source_max)).rgb;
    vec3 up = texture(u_video_projection, clamp(source_uv - vec2(0.0, texel.y), source_min, source_max)).rgb;
    return clamp(center * 0.40 + (right + left + down + up) * 0.15, vec3(0.0), vec3(1.0));
}

vec3 two_band_rgb(vec2 local_uv, vec3 camera_rgb, vec3 video_rgb, float camera_weight) {
    vec3 camera_low = guide_low_pass_rgb(local_uv);
    vec3 video_low = video_low_pass_rgb(v_uv);
    vec3 camera_high = camera_rgb - camera_low;
    vec3 video_high = video_rgb - video_low;
    float low_weight = smoothstep(0.0, 1.0, camera_weight);
    float high_weight = smoothstep(0.58, 1.0, camera_weight);
    return clamp(
        mix(video_low, camera_low, low_weight) + mix(video_high, camera_high, high_weight),
        vec3(0.0),
        vec3(1.0)
    );
}

void main() {
    float processing_mode = floor(pc.params.y + 0.5);
    float video_border_blend_active = 1.0 - step(0.5, abs(processing_mode - 2.0));
    if (video_border_blend_active < 0.5) {
        discard;
    }

    vec2 target_size = max(pc.target_rect.zw, vec2(0.0001));
    vec2 local_uv = (v_uv - pc.target_rect.xy) / target_size;
    float inside_target = uv_valid(local_uv);
    if (inside_target < 0.5) {
        discard;
    }

    float projection_area_opacity = clamp(pc.params.w, 0.0, 1.0);
    float signed_distance_uv = target_footprint_signed_distance_uv(local_uv);
    float projection_area_mask = 1.0 - step(0.0001, signed_distance_uv);
    float blend_weight = peripheral_stretch_blend_weight(signed_distance_uv);
    float camera_weight = projection_area_mask * (1.0 - blend_weight);

    vec3 guide_rgb = guide_sample_rgb(local_uv);
    vec3 video_rgb = video_sample_rgb();
    float video_opacity = clamp(pc.alpha.z, 0.0, 1.0);
    float output_alpha = mix(video_opacity, projection_area_opacity, camera_weight);
    float blend_mode = floor(pc.alpha.y + 0.5);
    float edge_weight = 1.0 - camera_weight;
    vec3 crossfade_rgb = mix(video_rgb, guide_rgb, camera_weight);

    vec3 composite_rgb;
    if (blend_mode > 10.5) {
        float stabilized_weight = smoothstep(0.0, 1.0, camera_weight);
        composite_rgb = mix(video_rgb, guide_rgb, stabilized_weight);
    } else if (blend_mode > 9.5) {
        composite_rgb = two_band_rgb(local_uv, guide_rgb, video_rgb, camera_weight);
    } else if (blend_mode > 8.5) {
        composite_rgb = gradient_aware_rgb(guide_rgb, video_rgb, camera_weight);
    } else if (blend_mode > 7.5) {
        composite_rgb = band_limited_mode_rgb(crossfade_rgb, video_rgb * guide_rgb, camera_weight);
    } else if (blend_mode > 6.5) {
        composite_rgb = band_limited_mode_rgb(crossfade_rgb, screen_rgb(video_rgb, guide_rgb), camera_weight);
    } else if (blend_mode > 5.5) {
        composite_rgb = band_limited_mode_rgb(crossfade_rgb, overlay_rgb(video_rgb, guide_rgb), camera_weight);
    } else if (blend_mode > 4.5) {
        composite_rgb = band_limited_mode_rgb(crossfade_rgb, soft_light_rgb(video_rgb, guide_rgb), camera_weight);
    } else if (blend_mode > 3.5) {
        composite_rgb = chroma_luma_split_rgb(guide_rgb, video_rgb, camera_weight, edge_weight);
    } else if (blend_mode > 2.5) {
        vec3 matched_guide = luma_matched_camera_rgb(guide_rgb, video_rgb, edge_weight);
        composite_rgb = mix(video_rgb, matched_guide, camera_weight);
    } else if (blend_mode > 1.5) {
        composite_rgb = linear_to_srgb(mix(
            srgb_to_linear(video_rgb),
            srgb_to_linear(guide_rgb),
            camera_weight
        ));
    } else {
        composite_rgb = crossfade_rgb;
    }

    out_color = vec4(composite_rgb * output_alpha, output_alpha);
}

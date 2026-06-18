#version 450

layout(set = 0, binding = 0) uniform sampler2D u_guide;

layout(push_constant) uniform GuideProjectionPush {
    vec4 target_rect;
    vec4 params;
    vec4 stretch0;
    vec4 stretch1;
    vec4 alpha;
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

vec2 projection_area_rect_edge_uv(
    vec2 canonical_uv,
    vec2 domain_min_uv,
    vec2 domain_max_uv
) {
    vec2 center = vec2(0.5);
    float core_scale = clamp(pc.stretch0.x, 0.05, 1.0);
    vec2 core_half_size = max(vec2(0.5) * core_scale, vec2(0.001));
    vec2 normalized = (canonical_uv - center) / core_half_size;
    float edge_distance = max(max(abs(normalized.x), abs(normalized.y)), 0.0001);
    vec2 edge_normalized = normalized / edge_distance;
    vec2 edge_direction_uv = edge_normalized * core_half_size;
    vec2 bounded_min_uv = min(domain_min_uv, domain_max_uv);
    vec2 bounded_max_uv = max(domain_min_uv, domain_max_uv);
    float default_reach = 1000000.0;
    float positive_x_reach =
        (bounded_max_uv.x - center.x) / max(edge_direction_uv.x, 0.0001);
    float negative_x_reach =
        (bounded_min_uv.x - center.x) / min(edge_direction_uv.x, -0.0001);
    float reach_x = mix(
        default_reach,
        mix(negative_x_reach, positive_x_reach, step(0.0, edge_direction_uv.x)),
        step(0.0001, abs(edge_direction_uv.x))
    );
    float positive_y_reach =
        (bounded_max_uv.y - center.y) / max(edge_direction_uv.y, 0.0001);
    float negative_y_reach =
        (bounded_min_uv.y - center.y) / min(edge_direction_uv.y, -0.0001);
    float reach_y = mix(
        default_reach,
        mix(negative_y_reach, positive_y_reach, step(0.0, edge_direction_uv.y)),
        step(0.0001, abs(edge_direction_uv.y))
    );
    float exterior_reach = max(min(reach_x, reach_y) - 1.0, 0.0001);
    float exterior_t = smoothstep(
        0.0,
        1.0,
        clamp((max(edge_distance, 1.0) - 1.0) / exterior_reach, 0.0, 1.0)
    );
    float edge_inset = clamp(pc.stretch0.y, 0.0, 0.49);
    float max_inset = clamp(pc.stretch0.z, edge_inset, 0.49);
    float curve = clamp(pc.stretch0.w, 0.25, 6.0);
    float inset = mix(edge_inset, max_inset, pow(exterior_t, curve));
    vec2 sample_half_size = max(core_half_size - vec2(inset), vec2(0.001));
    vec2 sample_uv = center + edge_normalized * sample_half_size;
    return clamp(sample_uv, bounded_min_uv, bounded_max_uv);
}

void main() {
    int eye = int(floor(pc.params.x + 0.5));
    float stretch_active = step(0.5, pc.params.y);
    float passthrough_border_policy = step(0.5, pc.params.z);
    float projection_area_opacity = clamp(pc.params.w, 0.0, 1.0);
    float projection_border_opacity = clamp(pc.alpha.x, 0.0, 1.0);
    float debug_mode = pc.stretch1.w;
    vec2 target_size = max(pc.target_rect.zw, vec2(0.0001));
    vec2 local_uv = (v_uv - pc.target_rect.xy) / target_size;
    float inside_target = uv_valid(local_uv);
    if (stretch_active < 0.5 && inside_target < 0.5) {
        discard;
    }

    vec2 domain_min_uv = vec2(-pc.target_rect.x / target_size.x, -pc.target_rect.y / target_size.y);
    vec2 domain_max_uv = vec2(
        (1.0 - pc.target_rect.x) / target_size.x,
        (1.0 - pc.target_rect.y) / target_size.y
    );
    float signed_distance_uv = target_footprint_signed_distance_uv(local_uv);
    float projection_area_mask = 1.0 - step(0.0001, signed_distance_uv);
    float stretch_weight = stretch_active * peripheral_stretch_blend_weight(signed_distance_uv);
    float stretch_exterior = stretch_active * (1.0 - projection_area_mask);
    float target_transition_band =
        stretch_active * projection_area_mask * step(0.0001, stretch_weight);
    float target_stretch_effect_region =
        clamp(max(stretch_exterior, target_transition_band), 0.0, 1.0);
    vec2 stretch_uv = projection_area_rect_edge_uv(local_uv, domain_min_uv, domain_max_uv);
    vec2 guide_uv = mix(
        local_uv,
        stretch_uv,
        clamp(stretch_weight, 0.0, 1.0) * target_stretch_effect_region
    );
    float projection_valid =
        clamp(max(projection_area_mask, target_stretch_effect_region), 0.0, 1.0);
    vec2 sample_uv = clamp(guide_uv, vec2(0.0001), vec2(0.9999));
    vec4 guide_color = texture(u_guide, sample_uv);
    vec3 border_color = eye == 0 ? vec3(0.0, 1.0, 0.82) : vec3(1.0, 0.72, 0.05);
    float edge = min(min(local_uv.x, 1.0 - local_uv.x), min(local_uv.y, 1.0 - local_uv.y));
    float border = 1.0 - smoothstep(0.0, 0.018, edge);
    vec3 rgb = mix(guide_color.rgb, border_color, border * 0.72 * (1.0 - stretch_active));

    if (stretch_active > 0.5) {
        if (debug_mode > 1.5 && target_stretch_effect_region > 0.5) {
            out_color = vec4(
                sample_uv.x,
                sample_uv.y,
                0.25 + 0.35 * target_transition_band,
                1.0
            );
            return;
        }
        float region_debug = step(0.5, debug_mode) * step(debug_mode, 1.5);
        vec3 transition_tint = mix(rgb, vec3(0.96, 1.0, 0.08), 0.42);
        vec3 exterior_tint = mix(rgb, vec3(0.0, 0.88, 1.0), 0.48);
        vec3 region_rgb = mix(
            mix(rgb, exterior_tint, stretch_exterior),
            transition_tint,
            target_transition_band
        );
        rgb = mix(rgb, region_rgb, region_debug * projection_valid);
    }

    vec3 matte = mix(vec3(1.0, 0.0, 0.0), vec3(0.0), passthrough_border_policy);
    vec3 window_rgb = mix(matte, rgb, projection_valid);
    float border_alpha = projection_border_opacity * (1.0 - passthrough_border_policy);
    float area_alpha = projection_area_opacity;
    float alpha = mix(border_alpha, area_alpha, projection_valid);
    vec3 premultiplied = clamp(window_rgb, vec3(0.0), vec3(1.0)) * alpha;
    out_color = vec4(premultiplied, alpha);
}

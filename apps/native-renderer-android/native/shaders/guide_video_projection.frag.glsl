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

vec3 video_sample_rgb() {
    vec2 video_target_size = max(pc.video_target_rect.zw, vec2(0.0001));
    vec2 video_local_uv = (v_uv - pc.video_target_rect.xy) / video_target_size;
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
    return clamp(texture(u_video_projection, source_uv).rgb, vec3(0.0), vec3(1.0));
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

    vec3 guide_rgb = clamp(texture(u_guide, clamp(local_uv, vec2(0.0001), vec2(0.9999))).rgb, vec3(0.0), vec3(1.0));
    vec3 video_rgb = video_sample_rgb();
    float video_opacity = clamp(pc.alpha.z, 0.0, 1.0);
    float output_alpha = mix(video_opacity, projection_area_opacity, camera_weight);
    float blend_mode = floor(pc.alpha.y + 0.5);

    vec3 composite_rgb;
    if (blend_mode > 2.5) {
        vec3 matched_guide = luma_matched_camera_rgb(guide_rgb, video_rgb, blend_weight);
        composite_rgb = mix(video_rgb, matched_guide, camera_weight);
    } else if (blend_mode > 1.5) {
        composite_rgb = linear_to_srgb(mix(
            srgb_to_linear(video_rgb),
            srgb_to_linear(guide_rgb),
            camera_weight
        ));
    } else {
        composite_rgb = mix(video_rgb, guide_rgb, camera_weight);
    }

    out_color = vec4(composite_rgb * output_alpha, output_alpha);
}

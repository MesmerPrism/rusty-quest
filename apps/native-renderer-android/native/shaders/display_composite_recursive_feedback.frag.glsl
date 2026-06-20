#version 450

layout(set = 0, binding = 0) uniform sampler2D u_display_composite;
layout(set = 0, binding = 1) uniform sampler2D u_previous_feedback;

layout(push_constant) uniform DisplayCompositeRecursiveFeedbackPush {
    vec4 source_uv_rect;
    vec4 params0;
    vec4 params1;
} pc;

layout(location = 0) in vec2 v_uv;
layout(location = 0) out vec4 out_color;

void main() {
    float flip_y = step(0.5, pc.params0.x);
    vec2 oriented_uv = vec2(v_uv.x, mix(v_uv.y, 1.0 - v_uv.y, flip_y));
    vec2 source_uv = pc.source_uv_rect.xy + oriented_uv * pc.source_uv_rect.zw;
    vec4 media = texture(u_display_composite, source_uv);

    float base_gain = clamp(pc.params1.y, 0.25, 1.0);
    vec3 base = mix(vec3(0.012, 0.016, 0.018), media.rgb, base_gain);

    float previous_alpha = clamp(pc.params0.y, 0.0, 1.0);
    float inset_scale = clamp(pc.params0.z, 0.35, 0.94);
    vec2 inset_min = vec2((1.0 - inset_scale) * 0.5);
    vec2 inset_uv = (v_uv - inset_min) / inset_scale;
    bool inside_inset =
        all(greaterThanEqual(inset_uv, vec2(0.0))) &&
        all(lessThanEqual(inset_uv, vec2(1.0)));

    if (inside_inset && previous_alpha > 0.001) {
        vec4 previous = texture(u_previous_feedback, inset_uv);
        float falloff = 0.94;
        base = mix(base, previous.rgb * falloff + vec3(0.006, 0.008, 0.010), previous_alpha);
    }

    float outer_edge = min(min(v_uv.x, 1.0 - v_uv.x), min(v_uv.y, 1.0 - v_uv.y));
    float outer_border = 1.0 - smoothstep(0.0, 0.010, outer_edge);

    float inset_edge = inside_inset
        ? min(min(inset_uv.x, 1.0 - inset_uv.x), min(inset_uv.y, 1.0 - inset_uv.y))
        : 1.0;
    float inset_border = inside_inset ? 1.0 - smoothstep(0.0, 0.020, inset_edge) : 0.0;

    float phase = fract(pc.params0.w);
    vec3 cyan = vec3(0.08, 0.82, 1.0);
    vec3 amber = vec3(1.0, 0.68, 0.18);
    vec3 border_color = mix(cyan, amber, smoothstep(0.15, 0.85, phase));
    float border_opacity = clamp(pc.params1.x, 0.0, 1.0);
    base = mix(base, border_color, max(outer_border * 0.55, inset_border) * border_opacity);

    out_color = vec4(clamp(base, vec3(0.0), vec3(1.0)), 1.0);
}

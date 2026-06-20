#version 450

layout(set = 0, binding = 0) uniform sampler2D u_display_composite;

layout(push_constant) uniform DisplayCompositeFeedbackPush {
    vec4 target_rect;
    vec4 source_uv_rect;
    vec4 params0;
} pc;

layout(location = 0) in vec2 v_uv;
layout(location = 0) out vec4 out_color;

void main() {
    vec2 target_size = max(pc.target_rect.zw, vec2(0.0001));
    vec2 local_uv = (v_uv - pc.target_rect.xy) / target_size;
    if (any(lessThan(local_uv, vec2(0.0))) || any(greaterThan(local_uv, vec2(1.0)))) {
        discard;
    }

    float flip_y = step(0.5, pc.params0.x);
    vec2 oriented_uv = vec2(local_uv.x, mix(local_uv.y, 1.0 - local_uv.y, flip_y));
    vec2 source_uv = pc.source_uv_rect.xy + oriented_uv * pc.source_uv_rect.zw;
    vec4 sample_color = texture(u_display_composite, source_uv);

    vec3 border_color = pc.params0.w < 0.5 ? vec3(0.18, 0.86, 1.0) : vec3(1.0, 0.72, 0.22);
    float edge = min(min(local_uv.x, 1.0 - local_uv.x), min(local_uv.y, 1.0 - local_uv.y));
    float border = 1.0 - smoothstep(0.0, 0.016, edge);
    float border_opacity = clamp(pc.params0.z, 0.0, 1.0);
    vec3 rgb = mix(sample_color.rgb, border_color, border * border_opacity);
    float plane_opacity = clamp(pc.params0.y, 0.0, 1.0);
    vec3 premultiplied = clamp(rgb, vec3(0.0), vec3(1.0)) * plane_opacity;
    out_color = vec4(premultiplied, plane_opacity);
}

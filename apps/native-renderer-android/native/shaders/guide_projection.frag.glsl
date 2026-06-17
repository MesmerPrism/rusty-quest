#version 450

layout(set = 0, binding = 0) uniform sampler2D u_guide;

layout(push_constant) uniform GuideProjectionPush {
    vec4 target_rect;
    vec4 params;
} pc;

layout(location = 0) in vec2 v_uv;
layout(location = 0) out vec4 out_color;

void main() {
    vec2 target_size = max(pc.target_rect.zw, vec2(0.0001));
    vec2 local_uv = (v_uv - pc.target_rect.xy) / target_size;
    if (any(lessThan(local_uv, vec2(0.0))) || any(greaterThan(local_uv, vec2(1.0)))) {
        discard;
    }

    vec4 guide_color = texture(u_guide, local_uv);
    vec3 border_color = pc.params.x < 0.5 ? vec3(0.0, 1.0, 0.82) : vec3(1.0, 0.72, 0.05);
    float edge = min(min(local_uv.x, 1.0 - local_uv.x), min(local_uv.y, 1.0 - local_uv.y));
    float border = 1.0 - smoothstep(0.0, 0.018, edge);
    vec3 rgb = mix(guide_color.rgb, border_color, border * 0.72);
    out_color = vec4(clamp(rgb, vec3(0.0), vec3(1.0)), 1.0);
}

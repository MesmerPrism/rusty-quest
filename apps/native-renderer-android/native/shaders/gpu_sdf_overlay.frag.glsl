#version 450

layout(set = 0, binding = 0) readonly buffer SdfField {
    vec4 cells[];
} sdf_field;

layout(push_constant) uniform SdfOverlayPush {
    vec4 target_rect;
    uvec4 dims;
    vec4 color;
} pc;

layout(location = 0) in vec2 v_uv;
layout(location = 0) out vec4 out_color;

void main() {
    vec2 target_size = max(pc.target_rect.zw, vec2(0.0001));
    vec2 local_uv = (v_uv - pc.target_rect.xy) / target_size;
    if (any(lessThan(local_uv, vec2(0.0))) || any(greaterThan(local_uv, vec2(1.0)))) {
        discard;
    }

    uint width = max(pc.dims.x, 1u);
    uint height = max(pc.dims.y, 1u);
    uint x = min(uint(local_uv.x * float(width)), width - 1u);
    uint y = min(uint(local_uv.y * float(height)), height - 1u);
    vec4 cell = sdf_field.cells[y * width + x];

    float signed_distance = cell.x;
    float edge_band = clamp(cell.y, 0.0, 1.0);
    float inside_fill = clamp(cell.z, 0.0, 1.0);
    float component = cell.w;

    vec3 component_tint = pc.color.rgb;
    if (component < 0.5) {
        component_tint = mix(pc.color.rgb, vec3(0.02, 0.88, 1.0), 0.55);
    } else if (component < 1.5) {
        component_tint = mix(pc.color.rgb, vec3(1.0, 0.66, 0.10), 0.42);
    } else {
        component_tint = mix(pc.color.rgb, vec3(1.0, 0.95, 0.14), 0.38);
    }

    float edge = smoothstep(0.18, 1.0, edge_band);
    float interior = inside_fill * smoothstep(-0.16, -0.005, -abs(signed_distance));
    float alpha = min((edge * 0.92 + interior * 0.28) * pc.color.a, pc.color.a);
    if (alpha <= 0.003) {
        discard;
    }
    out_color = vec4(component_tint * alpha, alpha);
}

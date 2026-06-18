#version 450

layout(location = 0) in vec2 v_mask_uv;
layout(location = 1) in vec4 v_color;
layout(location = 0) out vec4 out_color;

void main() {
    vec2 centered = v_mask_uv * 2.0 - vec2(1.0);
    float radius = length(centered);
    float white_value = smoothstep(1.0, 0.0, radius);
    white_value = white_value * white_value * (3.0 - 2.0 * white_value);
    float alpha = clamp(white_value * v_color.a, 0.0, 1.0);
    out_color = vec4(v_color.rgb * alpha, alpha);
}

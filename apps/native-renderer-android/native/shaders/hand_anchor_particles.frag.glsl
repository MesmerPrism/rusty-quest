#version 450

layout(location = 0) in vec2 v_mask_uv;
layout(location = 1) in vec4 v_color;
layout(location = 2) in vec4 v_render_params;
layout(location = 0) out vec4 out_color;

void main() {
    vec2 centered = v_mask_uv * 2.0 - vec2(1.0);
    float radius = length(centered);
    float white_value = smoothstep(1.0, 0.0, radius);
    white_value = white_value * white_value * (3.0 - 2.0 * white_value);
    float premultiply_rgb = v_render_params.x;
    float composition_mode = v_render_params.y;
    float depth_suppression_strength = max(v_render_params.z, 0.0);
    float view_depth_m = max(v_render_params.w, 0.0);
    float depth01 = clamp((view_depth_m - 0.08) / 0.55, 0.0, 1.0);
    float depth_weight = composition_mode > 0.5
        ? exp2(-depth_suppression_strength * depth01)
        : 1.0;
    float alpha = clamp(white_value * v_color.a * depth_weight, 0.0, 1.0);
    vec3 rgb = clamp(v_color.rgb, vec3(0.0), vec3(1.0));
    if (premultiply_rgb > 0.5) {
        rgb *= alpha;
    }
    out_color = vec4(rgb, alpha);
}

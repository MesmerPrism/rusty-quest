#version 450

layout(location = 0) in vec2 v_mask_uv;
layout(location = 1) in vec4 v_color;
layout(location = 2) in vec4 v_render_params;
layout(location = 0) out vec4 out_color;

void main() {
    vec2 centered = v_mask_uv * 2.0 - vec2(1.0);
    float radius = length(centered);
    float core = smoothstep(1.0, 0.0, radius);
    float feather = core * core * (3.0 - 2.0 * core);
    float view_depth_m = max(v_render_params.x, 0.0);
    float near_m = max(v_render_params.z, 0.01);
    float far_m = max(v_render_params.w, near_m + 0.01);
    float depth01 = clamp((view_depth_m - near_m) / max(far_m - near_m, 0.01), 0.0, 1.0);
    float alpha = clamp(feather * v_color.a * mix(1.0, 0.55, depth01), 0.0, 1.0);
    out_color = vec4(clamp(v_color.rgb, vec3(0.0), vec3(1.0)) * alpha, alpha);
}

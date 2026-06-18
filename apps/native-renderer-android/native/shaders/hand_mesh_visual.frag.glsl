#version 450

layout(location = 0) flat in uint v_component;
layout(location = 1) in float v_depth;
layout(location = 2) flat in float v_normal_z;
layout(location = 0) out vec4 out_color;

layout(push_constant) uniform HandMeshVisualPush {
    vec4 target_rect;
    vec4 params;
} pc;

void main() {
    vec3 surface_color = vec3(0.700, 0.935, 0.870);
    vec3 diagnostic_color = vec3(0.620, 0.965, 0.900);
    vec3 rgb = surface_color;
    float depth_tint = mix(0.97, 1.03, clamp(v_depth, 0.0, 1.0));
    float normal_tint = mix(0.99, 1.01, clamp(v_normal_z, 0.0, 1.0));
    float diagnostic = clamp(pc.params.w, 0.0, 1.0);
    float alpha = mix(0.62, clamp(pc.params.z, 0.2, 1.0), diagnostic);
    rgb = mix(rgb, diagnostic_color, diagnostic);
    rgb = clamp(rgb * depth_tint * normal_tint, vec3(0.0), vec3(1.0));
    out_color = vec4(rgb * alpha, alpha);
}

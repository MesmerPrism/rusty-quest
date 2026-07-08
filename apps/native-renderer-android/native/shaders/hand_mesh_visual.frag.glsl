#version 450

layout(location = 0) flat in uint v_component;
layout(location = 1) in float v_depth;
layout(location = 2) flat in float v_normal_z;
layout(location = 3) in vec3 v_barycentric;
layout(location = 0) out vec4 out_color;

layout(push_constant) uniform HandMeshVisualPush {
    vec4 target_rect;
    vec4 params;
    vec4 material;
    vec4 eye_position;
    vec4 eye_orientation_xyzw;
    vec4 fov_tangents;
    vec4 target0;
    vec4 target1;
} pc;

float wireframe_edge_alpha(float width_px) {
    if (width_px <= 0.0) {
        return 0.0;
    }
    vec3 derivatives = fwidth(v_barycentric);
    vec3 edge = smoothstep(vec3(0.0), max(derivatives * max(width_px, 0.001), vec3(0.000001)), v_barycentric);
    return 1.0 - min(min(edge.x, edge.y), edge.z);
}

void main() {
    vec3 surface_color = clamp(pc.material.rgb, vec3(0.0), vec3(1.0));
    vec3 diagnostic_color = vec3(0.620, 0.965, 0.900);
    float facing = clamp(v_normal_z, 0.0, 1.0);
    float depth_tint = mix(0.965, 1.035, clamp(v_depth, 0.0, 1.0));
    float normal_tint = mix(0.965, 1.030, facing);
    float rim = pow(1.0 - facing, 2.35) * clamp(pc.material.a, 0.0, 1.0);
    vec3 rim_color = mix(surface_color, vec3(0.90, 0.98, 1.00), 0.70);
    vec3 rgb = surface_color * depth_tint * normal_tint + rim_color * rim;
    float diagnostic = clamp(pc.params.w, 0.0, 1.0);
    float alpha = clamp(pc.params.z, 0.05, 1.0);
    rgb = mix(rgb, diagnostic_color, diagnostic);
    float wire_alpha = wireframe_edge_alpha(pc.eye_position.w);
    vec3 wire_color = mix(vec3(0.018, 0.105, 0.115), vec3(0.82, 0.98, 0.95), diagnostic * 0.25);
    rgb = mix(rgb, wire_color, wire_alpha);
    alpha = max(alpha, wire_alpha * clamp(alpha + 0.18, 0.05, 1.0));
    rgb = clamp(rgb, vec3(0.0), vec3(1.0));
    out_color = vec4(rgb * alpha, alpha);
}

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
    vec3 colors[6] = vec3[](
        vec3(0.561, 0.780, 0.690),
        vec3(0.843, 0.706, 0.416),
        vec3(0.820, 0.478, 0.424),
        vec3(0.694, 0.584, 0.792),
        vec3(0.898, 0.604, 0.373),
        vec3(0.612, 0.702, 0.859)
    );
    vec3 diagnostic_colors[6] = vec3[](
        vec3(0.000, 1.000, 0.950),
        vec3(1.000, 0.930, 0.000),
        vec3(1.000, 0.050, 0.820),
        vec3(0.570, 0.980, 1.000),
        vec3(1.000, 0.380, 0.000),
        vec3(0.200, 0.520, 1.000)
    );
    vec3 rgb = colors[v_component % 6u];
    float depth_tint = mix(0.74, 1.12, clamp(v_depth, 0.0, 1.0));
    float normal_tint = mix(0.86, 1.16, clamp(v_normal_z, 0.0, 1.0));
    float diagnostic = clamp(pc.params.w, 0.0, 1.0);
    float alpha = mix(v_component == 2u ? 0.55 : 0.42, clamp(pc.params.z, 0.2, 1.0), diagnostic);
    rgb = mix(rgb, diagnostic_colors[v_component % 6u], diagnostic);
    rgb = clamp(rgb * depth_tint * normal_tint, vec3(0.0), vec3(1.0));
    out_color = vec4(rgb * alpha, alpha);
}

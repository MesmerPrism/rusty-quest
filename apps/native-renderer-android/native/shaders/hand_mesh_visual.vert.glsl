#version 450

layout(set = 0, binding = 0) readonly buffer SkinnedTargetPositions {
    vec4 positions[];
} skinned_positions;

layout(set = 0, binding = 1) readonly buffer RecordedSkinningTriangles {
    uvec4 triangles[];
} skinning_triangles;

layout(push_constant) uniform HandMeshVisualPush {
    vec4 target_rect;
    vec4 params;
} pc;

layout(location = 0) flat out uint v_component;
layout(location = 1) out float v_depth;
layout(location = 2) flat out float v_normal_z;

void main() {
    uint triangle_index = uint(gl_VertexIndex) / 3u;
    uint corner_index = uint(gl_VertexIndex) - triangle_index * 3u;
    uvec4 triangle = skinning_triangles.triangles[triangle_index];
    uint vertex_index = triangle.x;
    if (corner_index == 1u) {
        vertex_index = triangle.y;
    } else if (corner_index == 2u) {
        vertex_index = triangle.z;
    }

    vec4 a = skinned_positions.positions[triangle.x];
    vec4 b = skinned_positions.positions[triangle.y];
    vec4 c = skinned_positions.positions[triangle.z];
    vec4 vertex = skinned_positions.positions[vertex_index];
    vec3 normal = normalize(cross(b.xyz - a.xyz, c.xyz - a.xyz));

    float diagnostic = clamp(pc.params.w, 0.0, 1.0);
    vec2 local_uv = vec2(0.5) + (vertex.xy - vec2(0.5)) * mix(1.0, 1.35, diagnostic);
    local_uv += pc.params.xy * diagnostic;
    vec2 screen_uv = pc.target_rect.xy + local_uv * pc.target_rect.zw;
    gl_Position = vec4(screen_uv * 2.0 - vec2(1.0), 0.0, 1.0);
    v_component = triangle.w;
    v_depth = clamp(vertex.z, 0.0, 1.0);
    v_normal_z = abs(normal.z);
}

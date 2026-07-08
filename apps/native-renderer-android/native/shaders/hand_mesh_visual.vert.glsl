#version 450

layout(set = 0, binding = 0) readonly buffer SkinnedWorldPositions {
    vec4 positions[];
} skinned_positions;

layout(set = 0, binding = 1) readonly buffer RecordedSkinningTriangles {
    uvec4 triangles[];
} skinning_triangles;

layout(set = 0, binding = 2) readonly buffer HandMeshGraftParams {
    vec4 source_palm_position_scale;
    vec4 source_palm_orientation_xyzw;
    vec4 target_position_scale[5];
    vec4 target_orientation_xyzw[5];
} graft_params;

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

layout(location = 0) flat out uint v_component;
layout(location = 1) out float v_depth;
layout(location = 2) flat out float v_normal_z;
layout(location = 3) out vec3 v_barycentric;

vec4 safe_normalize_quat(vec4 quat) {
    float length_sq = max(dot(quat, quat), 0.000000000001);
    return quat * inversesqrt(length_sq);
}

vec4 inverse_quat(vec4 quat) {
    vec4 q = safe_normalize_quat(quat);
    return vec4(-q.xyz, q.w);
}

vec3 rotate_by_quat(vec4 quat, vec3 vector) {
    vec4 q = safe_normalize_quat(quat);
    vec3 uv = cross(q.xyz, vector);
    vec3 uuv = cross(q.xyz, uv);
    return vector + uv * (2.0 * q.w) + uuv * 2.0;
}

vec3 world_to_eye(vec3 world) {
    return rotate_by_quat(
        inverse_quat(pc.eye_orientation_xyzw),
        world - pc.eye_position.xyz
    );
}

vec2 world_to_target_uv(vec3 world) {
    vec3 center = vec3(pc.target0.x, pc.target0.y, pc.target1.x);
    float radius = max(pc.target0.w, 0.000001);
    vec2 local = (world.xy - center.xy) / radius;
    float diagnostic = clamp(pc.params.w, 0.0, 1.0);
    vec2 local_uv = vec2(0.5 + local.x * 0.44, 0.55 - local.y * 0.44);
    local_uv = vec2(0.5) + (local_uv - vec2(0.5)) * mix(1.0, 1.35, diagnostic);
    local_uv += pc.params.xy * diagnostic;
    return pc.target_rect.xy + local_uv * pc.target_rect.zw;
}

vec4 world_to_eye_clip(vec3 world) {
    vec3 eye = world_to_eye(world);
    if (eye.z >= -0.0001) {
        return vec4(4.0, 4.0, 0.0, 1.0);
    }
    float forward = -eye.z;
    float x_over_z = eye.x / forward;
    float y_over_z = eye.y / forward;
    float left_tan = pc.fov_tangents.x;
    float right_tan = pc.fov_tangents.y;
    float down_tan = pc.fov_tangents.z;
    float up_tan = pc.fov_tangents.w;
    float ndc_x = ((x_over_z - left_tan) / max(right_tan - left_tan, 0.0001)) * 2.0 - 1.0;
    float screen_y = 1.0 - ((y_over_z - down_tan) / max(up_tan - down_tan, 0.0001));
    float ndc_y = screen_y * 2.0 - 1.0;
    return vec4(ndc_x, ndc_y, 0.0, 1.0);
}

float target_depth(vec3 world) {
    float min_z = pc.target0.z;
    float depth = max(pc.target1.y, 0.000001);
    return clamp((world.z - min_z) / depth, 0.0, 1.0);
}

vec3 graft_world_vertex(vec3 source_world, uint target_index) {
    vec4 target = graft_params.target_position_scale[target_index];
    float scale = max(target.w, 0.000001);
    vec3 source_local = rotate_by_quat(
        inverse_quat(graft_params.source_palm_orientation_xyzw),
        source_world - graft_params.source_palm_position_scale.xyz
    );
    return target.xyz + rotate_by_quat(
        graft_params.target_orientation_xyzw[target_index],
        source_local * scale
    );
}

vec3 graft_world_normal(vec3 source_normal, uint target_index) {
    vec3 source_local = rotate_by_quat(
        inverse_quat(graft_params.source_palm_orientation_xyzw),
        source_normal
    );
    return normalize(rotate_by_quat(graft_params.target_orientation_xyzw[target_index], source_local));
}

void main() {
    uint triangle_index = uint(gl_VertexIndex) / 3u;
    uint corner_index = uint(gl_VertexIndex) - triangle_index * 3u;
    v_barycentric = corner_index == 0u
        ? vec3(1.0, 0.0, 0.0)
        : (corner_index == 1u ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0));
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

    bool graft_copy_projection = pc.target1.w > 0.5;
    if (graft_copy_projection) {
        uint graft_index = min(uint(gl_InstanceIndex), 4u);
        if (graft_params.target_position_scale[graft_index].w <= 0.0) {
            gl_Position = vec4(4.0, 4.0, 0.0, 1.0);
            v_component = triangle.w;
            v_depth = 0.0;
            v_normal_z = 0.0;
            return;
        }
        vertex.xyz = graft_world_vertex(vertex.xyz, graft_index);
        normal = graft_world_normal(normal, graft_index);
    }

    bool world_eye_projection = pc.target1.z > 0.5;
    if (world_eye_projection) {
        gl_Position = world_to_eye_clip(vertex.xyz);
    } else {
        vec2 screen_uv = world_to_target_uv(vertex.xyz);
        gl_Position = vec4(screen_uv * 2.0 - vec2(1.0), 0.0, 1.0);
    }
    v_component = triangle.w;
    v_depth = target_depth(vertex.xyz);
    v_normal_z = abs(normal.z);
}

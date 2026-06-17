#version 450

layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;

struct SourceVertex {
    vec4 bind_position;
    uvec4 joint_indices;
    vec4 joint_weights;
};

struct Pose {
    vec4 translation_pad;
    vec4 rotation_xyzw;
};

layout(set = 0, binding = 0) readonly buffer RecordedSkinningSourceVertices {
    SourceVertex vertices[];
} source_vertices;

layout(set = 0, binding = 2) readonly buffer RuntimeJointPoses {
    Pose poses[];
} runtime_joint_poses;

layout(set = 0, binding = 3) readonly buffer TipLengthRows {
    vec4 rows[];
} tip_lengths;

layout(set = 0, binding = 4) readonly buffer BindJointPoses {
    Pose poses[];
} bind_joint_poses;

layout(set = 0, binding = 5) readonly buffer BindJointSources {
    uvec4 rows[];
} bind_joint_sources;

layout(set = 0, binding = 6) writeonly buffer SkinnedTargetPositions {
    vec4 positions[];
} skinned_positions;

layout(push_constant) uniform SdfComputePush {
    uvec4 dims;
    vec4 target0;
    vec4 target1;
    vec4 params;
} pc;

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

vec3 transform_point(Pose pose, vec3 point) {
    return rotate_by_quat(pose.rotation_xyzw, point) + pose.translation_pad.xyz;
}

vec3 inverse_transform_point(Pose pose, vec3 point) {
    return rotate_by_quat(inverse_quat(pose.rotation_xyzw), point - pose.translation_pad.xyz);
}

float tip_length_at(uint index) {
    vec4 row = tip_lengths.rows[index / 4u];
    uint lane = index % 4u;
    if (lane == 0u) {
        return row.x;
    }
    if (lane == 1u) {
        return row.y;
    }
    if (lane == 2u) {
        return row.z;
    }
    return row.w;
}

Pose current_bind_joint_pose(uint bind_joint_index) {
    uvec4 source = bind_joint_sources.rows[bind_joint_index];
    if (source.x == 0u) {
        return runtime_joint_poses.poses[source.y];
    }

    Pose parent = runtime_joint_poses.poses[source.w];
    float length = max(tip_length_at(source.z), 0.0);
    vec3 offset = rotate_by_quat(parent.rotation_xyzw, vec3(0.0, 0.0, -length));
    Pose result;
    result.translation_pad = vec4(parent.translation_pad.xyz + offset, 0.0);
    result.rotation_xyzw = parent.rotation_xyzw;
    return result;
}

vec3 transform_joint(uint joint_index, vec4 p) {
    Pose bind_pose = bind_joint_poses.poses[joint_index];
    Pose current_pose = current_bind_joint_pose(joint_index);
    return transform_point(current_pose, inverse_transform_point(bind_pose, p.xyz));
}

vec4 target_space_position(vec3 p) {
    vec3 center = vec3(pc.target0.x, pc.target0.y, pc.target1.x);
    float min_z = pc.target0.z;
    float radius = max(pc.target0.w, 0.000001);
    float depth = max(pc.target1.y, 0.000001);
    vec2 local = (p.xy - center.xy) / radius;
    float target_x = 0.5 + local.x * 0.44;
    float target_y = 0.55 - local.y * 0.44;
    float target_z = clamp((p.z - min_z) / depth, 0.0, 1.0);
    return vec4(target_x, target_y, target_z, 1.0);
}

void main() {
    uint index = gl_GlobalInvocationID.x;
    uint vertex_count = pc.dims.x;
    if (index >= vertex_count) {
        return;
    }

    SourceVertex vertex = source_vertices.vertices[index];
    vec4 p = vertex.bind_position;
    vec4 weights = vertex.joint_weights;
    uvec4 joints = vertex.joint_indices;
    float total_weight = weights.x + weights.y + weights.z + weights.w;
    if (total_weight > 0.0) {
        vec3 weighted =
            transform_joint(joints.x, p) * weights.x +
            transform_joint(joints.y, p) * weights.y +
            transform_joint(joints.z, p) * weights.z +
            transform_joint(joints.w, p) * weights.w;
        skinned_positions.positions[index] = target_space_position(weighted / total_weight);
    } else {
        skinned_positions.positions[index] = target_space_position(p.xyz);
    }
}

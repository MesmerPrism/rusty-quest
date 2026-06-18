#version 450

layout(local_size_x = 128) in;

layout(set = 0, binding = 0) readonly buffer ParticleOutput {
    vec4 rows[];
} particle_output;

layout(set = 0, binding = 1) buffer ParticleSortRows {
    uvec4 rows[];
} sort_rows;

layout(push_constant) uniform ParticleSortPush {
    vec4 params0;
    vec4 params1;
    vec4 params2;
} pc;

const uint SORT_MODE_INIT = 0u;
const uint SORT_MODE_BITONIC = 1u;

void main() {
    uint index = gl_GlobalInvocationID.x;
    uint particle_count = uint(pc.params0.x);
    uint sort_count = uint(pc.params0.y);
    uint mode = uint(pc.params0.z);
    uint j = uint(pc.params0.w);
    uint k = uint(pc.params1.w);

    if (index >= sort_count) {
        return;
    }

    if (mode == SORT_MODE_INIT) {
        if (index < particle_count) {
            vec3 position = particle_output.rows[index * 4u].xyz;
            vec3 eye_position = pc.params1.xyz;
            vec3 eye_forward = normalize(pc.params2.xyz);
            float depth_m = max(dot(position - eye_position, eye_forward), 0.000001);
            uint key = floatBitsToUint(depth_m);
            sort_rows.rows[index] = uvec4(index, key, 0u, 0u);
        } else {
            sort_rows.rows[index] = uvec4(0u, 0u, 0u, 0u);
        }
        return;
    }

    if (mode != SORT_MODE_BITONIC) {
        return;
    }

    uint partner = index ^ j;
    if (partner <= index || partner >= sort_count) {
        return;
    }

    uvec4 left = sort_rows.rows[index];
    uvec4 right = sort_rows.rows[partner];
    bool descending_segment = (index & k) == 0u;
    bool swap_rows = descending_segment
        ? (left.y < right.y)
        : (left.y > right.y);

    if (swap_rows) {
        sort_rows.rows[index] = right;
        sort_rows.rows[partner] = left;
    }
}

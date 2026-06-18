#version 450

layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;

const uint SDF_TILE_GRID_WIDTH = 16u;
const uint SDF_TILE_GRID_HEIGHT = 12u;
const uint SDF_MAX_TRIANGLES_PER_TILE = 1024u;

layout(set = 0, binding = 1) readonly buffer RecordedSkinningTriangles {
    uvec4 triangles[];
} skinning_triangles;

layout(set = 0, binding = 6) readonly buffer SkinnedWorldPositions {
    vec4 positions[];
} skinned_positions;

layout(set = 0, binding = 8) coherent buffer SdfTileHeaders {
    uvec4 headers[];
} tile_headers;

layout(set = 0, binding = 9) writeonly buffer SdfTileIndices {
    uint indices[];
} tile_indices;

layout(set = 0, binding = 10) writeonly buffer SdfTriangleBounds {
    vec4 bounds[];
} triangle_bounds;

layout(push_constant) uniform SdfComputePush {
    uvec4 dims;
    vec4 target0;
    vec4 target1;
    vec4 params;
} pc;

vec2 target_space_uv(vec3 world) {
    vec3 center = vec3(pc.target0.x, pc.target0.y, pc.target1.x);
    float radius = max(pc.target0.w, 0.000001);
    vec2 local = (world.xy - center.xy) / radius;
    return vec2(0.5 + local.x * 0.44, 0.55 - local.y * 0.44);
}

void main() {
    uint triangle_index = gl_GlobalInvocationID.x;
    uint vertex_count = pc.dims.x;
    uint triangle_count = pc.dims.y;
    if (triangle_index >= triangle_count) {
        return;
    }

    uvec4 triangle = skinning_triangles.triangles[triangle_index];
    if (triangle.x >= vertex_count || triangle.y >= vertex_count || triangle.z >= vertex_count) {
        triangle_bounds.bounds[triangle_index] = vec4(2.0, 2.0, -1.0, -1.0);
        return;
    }

    vec2 a = target_space_uv(skinned_positions.positions[triangle.x].xyz);
    vec2 b = target_space_uv(skinned_positions.positions[triangle.y].xyz);
    vec2 c = target_space_uv(skinned_positions.positions[triangle.z].xyz);
    float band_radius = max(pc.params.x, 0.0001);
    vec2 min_uv = clamp(min(a, min(b, c)) - vec2(band_radius), vec2(0.0), vec2(1.0));
    vec2 max_uv = clamp(max(a, max(b, c)) + vec2(band_radius), vec2(0.0), vec2(1.0));
    triangle_bounds.bounds[triangle_index] = vec4(min_uv, max_uv);

    uvec2 min_tile = uvec2(
        min(uint(floor(min_uv.x * float(SDF_TILE_GRID_WIDTH))), SDF_TILE_GRID_WIDTH - 1u),
        min(uint(floor(min_uv.y * float(SDF_TILE_GRID_HEIGHT))), SDF_TILE_GRID_HEIGHT - 1u)
    );
    uvec2 max_tile = uvec2(
        min(uint(floor(max_uv.x * float(SDF_TILE_GRID_WIDTH))), SDF_TILE_GRID_WIDTH - 1u),
        min(uint(floor(max_uv.y * float(SDF_TILE_GRID_HEIGHT))), SDF_TILE_GRID_HEIGHT - 1u)
    );

    for (uint tile_y = min_tile.y; tile_y <= max_tile.y; tile_y++) {
        for (uint tile_x = min_tile.x; tile_x <= max_tile.x; tile_x++) {
            uint tile_index = tile_y * SDF_TILE_GRID_WIDTH + tile_x;
            uint slot = atomicAdd(tile_headers.headers[tile_index].x, 1u);
            if (slot < SDF_MAX_TRIANGLES_PER_TILE) {
                tile_indices.indices[tile_index * SDF_MAX_TRIANGLES_PER_TILE + slot] =
                    triangle_index;
            }
        }
    }
}

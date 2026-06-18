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

layout(set = 0, binding = 7) writeonly buffer SdfField {
    vec4 cells[];
} sdf_field;

layout(set = 0, binding = 8) readonly buffer SdfTileHeaders {
    uvec4 headers[];
} tile_headers;

layout(set = 0, binding = 9) readonly buffer SdfTileIndices {
    uint indices[];
} tile_indices;

layout(set = 0, binding = 10) readonly buffer SdfTriangleBounds {
    vec4 bounds[];
} triangle_bounds;

layout(push_constant) uniform SdfComputePush {
    uvec4 dims;
    vec4 target0;
    vec4 target1;
    vec4 params;
} pc;

float cross2(vec2 left, vec2 right);
float distance_to_segment(vec2 point, vec2 a, vec2 b);
bool point_in_triangle(vec2 point, vec2 a, vec2 b, vec2 c);
vec2 target_space_uv(vec3 world);

void main() {
    uint linear_index = gl_GlobalInvocationID.x;
    uint vertex_count = pc.dims.x;
    uint triangle_count = pc.dims.y;
    uint width = pc.dims.z;
    uint height = pc.dims.w;
    uint cell_count = width * height;
    if (linear_index >= cell_count) {
        return;
    }

    uint y = linear_index / width;
    uint x = linear_index - y * width;
    vec2 uv = (vec2(x, y) + vec2(0.5)) / vec2(width, height);
    float unsigned_min_distance = 10.0;
    float nearest_component = 0.0;
    bool inside_any_triangle = false;
    uint tile_x = min(x * SDF_TILE_GRID_WIDTH / width, SDF_TILE_GRID_WIDTH - 1u);
    uint tile_y = min(y * SDF_TILE_GRID_HEIGHT / height, SDF_TILE_GRID_HEIGHT - 1u);
    uint tile_index = tile_y * SDF_TILE_GRID_WIDTH + tile_x;
    uint tile_triangle_count = min(tile_headers.headers[tile_index].x, SDF_MAX_TRIANGLES_PER_TILE);

    for (uint slot = 0u; slot < tile_triangle_count; slot++) {
        uint triangle_index =
            tile_indices.indices[tile_index * SDF_MAX_TRIANGLES_PER_TILE + slot];
        if (triangle_index >= triangle_count) {
            continue;
        }
        vec4 bounds = triangle_bounds.bounds[triangle_index];
        if (
            uv.x < bounds.x || uv.y < bounds.y ||
            uv.x > bounds.z || uv.y > bounds.w
        ) {
            continue;
        }
        uvec4 triangle = skinning_triangles.triangles[triangle_index];
        if (triangle.x >= vertex_count || triangle.y >= vertex_count || triangle.z >= vertex_count) {
            continue;
        }
        vec2 a = target_space_uv(skinned_positions.positions[triangle.x].xyz);
        vec2 b = target_space_uv(skinned_positions.positions[triangle.y].xyz);
        vec2 c = target_space_uv(skinned_positions.positions[triangle.z].xyz);

        float triangle_distance = min(
            distance_to_segment(uv, a, b),
            min(distance_to_segment(uv, b, c), distance_to_segment(uv, c, a))
        );
        if (triangle_distance < unsigned_min_distance) {
            unsigned_min_distance = triangle_distance;
            nearest_component = float(triangle.w);
        }
        inside_any_triangle = inside_any_triangle || point_in_triangle(uv, a, b, c);
    }

    float band_radius = max(pc.params.x, 0.0001);
    float signed_distance = inside_any_triangle ? -unsigned_min_distance : unsigned_min_distance;
    float edge_band = clamp(1.0 - abs(signed_distance) / band_radius, 0.0, 1.0);
    float fill = inside_any_triangle ? 1.0 : 0.0;
    sdf_field.cells[linear_index] = vec4(signed_distance, edge_band, fill, nearest_component);
}

float cross2(vec2 left, vec2 right) {
    return left.x * right.y - left.y * right.x;
}

float distance_to_segment(vec2 point, vec2 a, vec2 b) {
    vec2 segment = b - a;
    float segment_len_sq = max(dot(segment, segment), 0.0000001);
    float t = clamp(dot(point - a, segment) / segment_len_sq, 0.0, 1.0);
    return distance(point, a + t * segment);
}

bool point_in_triangle(vec2 point, vec2 a, vec2 b, vec2 c) {
    float area = cross2(b - a, c - a);
    if (abs(area) < 0.0000001) {
        return false;
    }
    float edge0 = cross2(b - a, point - a);
    float edge1 = cross2(c - b, point - b);
    float edge2 = cross2(a - c, point - c);
    bool has_negative = edge0 < 0.0 || edge1 < 0.0 || edge2 < 0.0;
    bool has_positive = edge0 > 0.0 || edge1 > 0.0 || edge2 > 0.0;
    return !(has_negative && has_positive);
}

vec2 target_space_uv(vec3 world) {
    vec3 center = vec3(pc.target0.x, pc.target0.y, pc.target1.x);
    float radius = max(pc.target0.w, 0.000001);
    vec2 local = (world.xy - center.xy) / radius;
    return vec2(0.5 + local.x * 0.44, 0.55 - local.y * 0.44);
}

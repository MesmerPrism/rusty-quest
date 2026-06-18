#version 450

layout(set = 0, binding = 0) uniform sampler2D u_camera_left;
layout(set = 0, binding = 1) uniform sampler2D u_camera_right;

layout(push_constant) uniform CameraProjectionPush {
    vec4 params0;
    vec4 target_rect;
    vec4 params2;
} pc;

layout(location = 0) in vec2 v_uv;
layout(location = 0) out vec4 out_color;

void main() {
    int eye = int(floor(pc.params0.x + 0.5));
    vec2 target_size = max(pc.target_rect.zw, vec2(0.0001));
    vec2 local_uv = (v_uv - pc.target_rect.xy) / target_size;
    if (any(lessThan(local_uv, vec2(0.0))) || any(greaterThan(local_uv, vec2(1.0)))) {
        discard;
    }

    float flip_y = step(0.5, pc.params0.y);
    vec2 uv = vec2(local_uv.x, mix(local_uv.y, 1.0 - local_uv.y, flip_y));
    vec4 sample_color = eye == 0 ? texture(u_camera_left, uv) : texture(u_camera_right, uv);
    vec3 border_color = eye == 0 ? vec3(0.0, 1.0, 0.82) : vec3(1.0, 0.72, 0.05);
    float edge = min(min(local_uv.x, 1.0 - local_uv.x), min(local_uv.y, 1.0 - local_uv.y));
    float border = 1.0 - smoothstep(0.0, 0.018, edge);
    float border_opacity = clamp(pc.params2.x, 0.0, 1.0);
    vec3 rgb = mix(sample_color.rgb, border_color, border * border_opacity);
    out_color = vec4(clamp(rgb, vec3(0.0), vec3(1.0)), 1.0);
}

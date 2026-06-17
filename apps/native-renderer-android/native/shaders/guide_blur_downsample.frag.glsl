#version 450

layout(set = 0, binding = 0) uniform sampler2D u_camera_left;
layout(set = 0, binding = 1) uniform sampler2D u_camera_right;

layout(push_constant) uniform GuideDownsamplePush {
    vec4 params;
} pc;

layout(location = 0) in vec2 v_uv;
layout(location = 0) out vec4 out_color;

void main() {
    int eye = int(floor(pc.params.x + 0.5));
    float flip_y = step(0.5, pc.params.y);
    vec2 uv = vec2(v_uv.x, mix(v_uv.y, 1.0 - v_uv.y, flip_y));
    vec4 sample_color = eye == 0 ? texture(u_camera_left, uv) : texture(u_camera_right, uv);
    out_color = vec4(clamp(sample_color.rgb, vec3(0.0), vec3(1.0)), 1.0);
}

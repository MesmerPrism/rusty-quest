#version 450

layout(set = 0, binding = 0) uniform sampler2D u_source;

layout(push_constant) uniform GuideBlurPush {
    vec4 texel_step;
} pc;

layout(location = 0) in vec2 v_uv;
layout(location = 0) out vec4 out_color;

void main() {
    vec2 step_uv = pc.texel_step.xy;
    vec3 rgb = vec3(0.0);
    rgb += texture(u_source, v_uv - 2.0 * step_uv).rgb;
    rgb += texture(u_source, v_uv - step_uv).rgb;
    rgb += texture(u_source, v_uv).rgb;
    rgb += texture(u_source, v_uv + step_uv).rgb;
    rgb += texture(u_source, v_uv + 2.0 * step_uv).rgb;
    out_color = vec4(clamp(rgb * 0.2, vec3(0.0), vec3(1.0)), 1.0);
}

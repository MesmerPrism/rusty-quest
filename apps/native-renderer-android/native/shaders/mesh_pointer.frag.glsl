#version 450
// App-owned Vulkan ray/reticle; no Android UI image or runtime-owned graphics.
layout(push_constant) uniform PanelPush {
    vec4 centerWidth; vec4 rightHeight; vec4 upNear; vec4 eyeFar;
    vec4 eyeOrientation; vec4 fov; vec4 colorMode;
} pc;
layout(location=0) in vec2 uv;
layout(location=0) out vec4 outColor;
void main() {
    if (pc.colorMode.w > 0.5) {
        float radius = length(uv - vec2(0.5));
        if (radius > 0.5 || (radius > 0.13 && radius < 0.32)) discard;
    }
    outColor = vec4(pc.colorMode.rgb, 1.0);
}

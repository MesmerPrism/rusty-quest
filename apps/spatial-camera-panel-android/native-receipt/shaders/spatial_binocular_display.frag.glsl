#version 450

layout(set = 0, binding = 0) uniform sampler2D completedFrame;
layout(push_constant) uniform EyeTransforms {
    ivec4 eyes[2];
} transforms;
layout(location = 0) out vec4 outColor;

void main() {
    ivec2 size = textureSize(completedFrame, 0);
    int eyeWidth = size.x / 2;
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec4 mapping = transforms.eyes[pixel.x / eyeWidth];
    int x = pixel.x % eyeWidth;
    int y = pixel.y;
    if (mapping.y < 0) {
        x = eyeWidth - 1 - x;
    }
    if (mapping.z < 0) {
        y = size.y - 1 - y;
    }
    outColor = texelFetch(completedFrame, ivec2(mapping.x * eyeWidth + x, y), 0);
}

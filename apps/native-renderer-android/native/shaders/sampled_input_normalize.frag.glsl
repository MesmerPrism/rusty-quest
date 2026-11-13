#version 450

// The import owner supplies binding 0 INCLUDING its immutable conversion sampler.
// Copy the converted RGBA result to a regular UNORM attachment. No effect,
// geometry, orientation, opacity, or transfer-function change belongs here.
layout(set = 0, binding = 0) uniform sampler2D input_image;
layout(location = 0) in vec2 v_uv;
layout(location = 0) out vec4 out_color;
void main() {
    out_color = texture(input_image, v_uv);
}

#version 450
layout(location=0) in vec2 uv;
layout(location=1) in vec3 tint;
layout(location=2) flat in int particle;
layout(location=0) out vec4 color;
void main() {
    if(particle==1) {
        if(length(uv-.5)>.5) discard;
        color=vec4(tint,1);
    } else {
        float checker=mod(floor(uv.x*8)+floor(uv.y*8),2);
        color=vec4(tint*mix(.35,1.0,checker),1);
    }
}

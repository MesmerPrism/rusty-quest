#version 450
layout(push_constant) uniform PanelPush {
    vec4 centerWidth;
    vec4 rightHeight;
    vec4 upNear;
    vec4 eyeFar;
    vec4 eyeOrientation;
    vec4 fov;
    vec4 colorMode;
} pc;
layout(location=0) out vec2 uv;
vec3 rotateQuat(vec4 q,vec3 p) {return p+2.0*cross(q.xyz,cross(q.xyz,p)+q.w*p);}
void main() {
    const vec2 corners[6]=vec2[](vec2(0,0),vec2(0,1),vec2(1,1),vec2(0,0),vec2(1,1),vec2(1,0));
    uv=corners[gl_VertexIndex];
    vec3 world=pc.centerWidth.xyz+(uv.x-.5)*pc.centerWidth.w*pc.rightHeight.xyz+(.5-uv.y)*pc.rightHeight.w*pc.upNear.xyz;
    vec4 inverseEye=vec4(-pc.eyeOrientation.xyz,pc.eyeOrientation.w);
    vec3 eye=rotateQuat(inverseEye,world-pc.eyeFar.xyz);
    float forward=-eye.z;
    float x=(2.0*eye.x-(pc.fov.y+pc.fov.x)*forward)/(pc.fov.y-pc.fov.x);
    float y=((pc.fov.w+pc.fov.z)*forward-2.0*eye.y)/(pc.fov.w-pc.fov.z);
    float n=pc.upNear.w,f=pc.eyeFar.w;
    gl_Position=vec4(x,y,f/(f-n)*forward-f*n/(f-n),forward);
}

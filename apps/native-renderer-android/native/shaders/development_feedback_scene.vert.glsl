#version 450
layout(push_constant) uniform ScenePush {
    vec4 centerWidth;
    vec4 rightHeight;
    vec4 upNear;
    vec4 eyeFar;
    vec4 eyeOrientation;
    vec4 fov;
    vec4 parameters;
} pc;
layout(location=0) out vec2 uv;
layout(location=1) out vec3 tint;
layout(location=2) flat out int particle;
vec3 rotateQuat(vec4 q,vec3 p) { return p+2.0*cross(q.xyz,cross(q.xyz,p)+q.w*p); }
void main() {
    const vec2 corners[6]=vec2[](vec2(0,0),vec2(0,1),vec2(1,1),vec2(0,0),vec2(1,1),vec2(1,0));
    uv=corners[gl_VertexIndex%6];
    particle=gl_InstanceIndex == 0 ? 0 : 1;
    float variant=pc.parameters.y;
    tint=variant < 7.5 ? vec3(.08,.42,1.0) : vec3(1.0,.28,.035);
    vec3 local;
    if(particle==0) {
        const vec3 cube[8]=vec3[](vec3(-1,-1,-1),vec3(1,-1,-1),vec3(1,1,-1),vec3(-1,1,-1),vec3(-1,-1,1),vec3(1,-1,1),vec3(1,1,1),vec3(-1,1,1));
        const int indices[36]=int[](0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,3,7,6,3,6,2,0,4,7,0,7,3,1,2,6,1,6,5);
        vec3 vertex=cube[indices[gl_VertexIndex]]*.20;
        float angle=pc.parameters.x*.45;
        local=vec3(cos(angle)*vertex.x+sin(angle)*vertex.z,vertex.y,-sin(angle)*vertex.x+cos(angle)*vertex.z)+vec3(-.83,0,-.1);
    } else {
        if(gl_VertexIndex>=6 || pc.parameters.w < .5) { gl_Position=vec4(3,3,3,1); return; }
        float index=float(gl_InstanceIndex-1);
        float spacing=.045+variant*.002;
        float motion=sin(pc.parameters.x+index*.71)*.035;
        local=vec3(.72+(mod(index,8.0)-3.5)*spacing,(floor(index/8.0)-3.5)*spacing+motion,0);
        local.xy+=(uv-.5)*(.018+pc.parameters.z*.025);
        tint=mix(tint,vec3(.4,1,.7),index/80.0);
    }
    vec3 normal=cross(pc.rightHeight.xyz,pc.upNear.xyz);
    vec3 world=pc.centerWidth.xyz+local.x*pc.rightHeight.xyz+local.y*pc.upNear.xyz+local.z*normal;
    vec3 eye=rotateQuat(vec4(-pc.eyeOrientation.xyz,pc.eyeOrientation.w),world-pc.eyeFar.xyz);
    float forward=-eye.z;
    float x=(2.0*eye.x-(pc.fov.y+pc.fov.x)*forward)/(pc.fov.y-pc.fov.x);
    float y=((pc.fov.w+pc.fov.z)*forward-2.0*eye.y)/(pc.fov.w-pc.fov.z);
    float n=pc.upNear.w,f=pc.eyeFar.w;
    gl_Position=vec4(x,y,f/(f-n)*forward-f*n/(f-n),forward);
}

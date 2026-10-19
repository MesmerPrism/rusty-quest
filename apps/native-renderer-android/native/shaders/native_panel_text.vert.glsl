#version 450
layout(push_constant) uniform PanelPush { vec4 centerWidth; vec4 rightHeight; vec4 upNear; vec4 eyeFar; vec4 eyeOrientation; vec4 fov; vec4 atlasScale; } pc;
layout(location=0) in vec4 rect;
layout(location=1) in vec4 uvBounds;
layout(location=2) in vec4 color;
layout(location=0) out vec2 uv;
layout(location=1) flat out vec4 tint;
layout(location=2) flat out vec2 atlasMaximum;
vec3 rotateQuat(vec4 q,vec3 p){return p+2.0*cross(q.xyz,cross(q.xyz,p)+q.w*p);}
void main(){
 const vec2 corners[6]=vec2[](vec2(0,0),vec2(0,1),vec2(1,1),vec2(0,0),vec2(1,1),vec2(1,0));
 vec2 corner=corners[gl_VertexIndex];
 vec2 p=rect.xy+(corner*2.0-1.0)*rect.zw;
 vec3 world=pc.centerWidth.xyz+(p.x*pc.rightHeight.w)*pc.rightHeight.xyz-(p.y*pc.rightHeight.w)*pc.upNear.xyz;
 vec3 eye=rotateQuat(vec4(-pc.eyeOrientation.xyz,pc.eyeOrientation.w),world-pc.eyeFar.xyz);
 float forward=-eye.z;
 float x=(2.0*eye.x-(pc.fov.y+pc.fov.x)*forward)/(pc.fov.y-pc.fov.x);
 float y=((pc.fov.w+pc.fov.z)*forward-2.0*eye.y)/(pc.fov.w-pc.fov.z);
 float n=pc.upNear.w,f=pc.eyeFar.w;
 gl_Position=vec4(x,y,f/(f-n)*forward-f*n/(f-n),forward);
 uv=mix(uvBounds.xy,uvBounds.zw,corner)*pc.atlasScale.xy;
 atlasMaximum=pc.atlasScale.xy-vec2(0.5/1024.0);
 tint=color;
}

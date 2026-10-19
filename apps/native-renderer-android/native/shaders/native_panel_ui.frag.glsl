#version 450
layout(location=0) in vec2 local;
layout(location=1) flat in vec4 boundsRadius;
layout(location=2) flat in vec4 tint;
layout(location=0) out vec4 outColor;
void main(){
 float r=clamp(boundsRadius.z,0.0,min(boundsRadius.x,boundsRadius.y));
 vec2 q=abs(local)-boundsRadius.xy+r;
 float d=length(max(q,0.0))+min(max(q.x,q.y),0.0)-r;
 float aa=max(fwidth(d),0.000001);
 float coverage=1.0-smoothstep(-aa,aa,d);
 float a=clamp(tint.a,0.0,1.0)*coverage;
 if(a<=0.001) discard;
 outColor=vec4(tint.rgb*a,a);
}

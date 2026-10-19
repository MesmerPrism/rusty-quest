#version 450
layout(set=0,binding=0) uniform sampler2D glyphAtlas;
layout(location=0) in vec2 uv;
layout(location=1) flat in vec4 tint;
layout(location=2) flat in vec2 atlasMaximum;
layout(location=0) out vec4 outColor;
void main(){
 float distance=texture(glyphAtlas,clamp(uv,vec2(0.5/1024.0),atlasMaximum)).r;
 float aa=max(fwidth(distance)*0.5,1.0/255.0);
 float coverage=smoothstep(0.5-aa,0.5+aa,distance);
 float a=clamp(tint.a,0.0,1.0)*coverage;
 if(a<=0.001) discard;
 outColor=vec4(tint.rgb*a,a);
}

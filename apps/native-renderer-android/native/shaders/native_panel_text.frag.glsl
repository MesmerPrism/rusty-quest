#version 450
layout(set=0,binding=0) uniform sampler2D glyphAtlas;
layout(location=0) in vec2 uv;
layout(location=1) flat in vec4 tint;
layout(location=2) flat in vec2 atlasMaximum;
layout(location=3) flat in vec4 tileBounds;
layout(location=0) out vec4 outColor;
layout(push_constant) uniform PanelClip { layout(offset=112) vec4 clip; } pc;
layout(location=4) in vec2 panelPosition;
void main(){
 vec2 dx=dFdx(uv),dy=dFdy(uv),size=vec2(textureSize(glyphAtlas,0));
 // UV derivatives track the screen footprint even where the SDF has a local extremum.
 float sourcePerPixel=max(length(dx*size),length(dy*size));
 vec2 halfTexel=0.5/size;
 vec2 low=max(tileBounds.xy+halfTexel,halfTexel);
 vec2 high=min(tileBounds.zw-halfTexel,atlasMaximum);
 float coverage=0.0;
 // Integrate four half-pixel samples. The atlas encodes +/-8 source texels in [0,1].
 float sourcePerSample=max(sourcePerPixel*0.5,0.0001);
 for(int y=0;y<2;++y) for(int x=0;x<2;++x){
  vec2 offset=(vec2(x,y)+vec2(0.5))*0.5-vec2(0.5);
  float distance=textureLod(glyphAtlas,clamp(uv+dx*offset.x+dy*offset.y,low,high),0.0).r;
  coverage+=clamp(0.5+(distance-0.5)*16.0/sourcePerSample,0.0,1.0);
 }
 coverage*=0.25;
 float a=clamp(tint.a,0.0,1.0)*coverage;
 if(a<=0.001 || any(lessThan(panelPosition,pc.clip.xy)) || any(greaterThanEqual(panelPosition,pc.clip.zw))) discard;
 outColor=vec4(tint.rgb*a,a);
}

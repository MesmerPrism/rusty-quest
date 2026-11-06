#version 430
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable
layout(std140,set=3,binding=0) uniform MaterialUniform {
    vec4 transportFlags; vec4 meshFlags; vec4 renderStyle0; vec4 renderStyle1; vec4 renderStyle2;
} material;
layout(set=3,binding=1) uniform sampler2D atlas;
layout(set=3,binding=2) uniform sampler2D maskAtlas;
layout(location=0) in vec4 workloadColor;
layout(location=1) in vec2 localUv;
layout(location=2) flat in uint flags;
layout(location=3) flat in uint drawable;
layout(location=4) flat in uint particle;
layout(location=5) flat in float colorAttenuation;
layout(location=0) out vec4 outColor;
float maskByte(ivec2 p){
    // SceneTexture is sRGB. Restore each R8 value before interpolating, matching
    // the source R8 UNORM mask rather than interpolating in the sRGB transfer space.
    float linear=texelFetch(maskAtlas,p,0).r;
    return linear<=.0031308?12.92*linear:1.055*pow(max(linear,0),1.0/2.4)-.055;
}
float maskLinear(ivec2 tile){
    vec2 at=clamp(localUv*128.0-.5,vec2(0),vec2(127));
    ivec2 low=ivec2(floor(at)),high=min(low+ivec2(1),ivec2(127));vec2 fraction=fract(at);
    return mix(mix(maskByte(tile+low),maskByte(tile+ivec2(high.x,low.y)),fraction.x),
        mix(maskByte(tile+ivec2(low.x,high.y)),maskByte(tile+high),fraction.x),fraction.y);
}
void main(){
    if(drawable==0u)discard;
    vec4 c=workloadColor;
    if(particle==2u&&length(localUv*2.0-1.0)>1.0)discard;
    if(particle==1u){
        uint layer=(flags>>4u)&63u;ivec2 tile=ivec2(int(layer%8u),int(layer/8u))*128;
        ivec2 p=clamp(ivec2(floor(localUv*128.0)),ivec2(0),ivec2(127));
        bool selected=material.meshFlags.w>.5;
        float mask=selected?maskLinear(tile):maskByte(tile+p);
        if(selected){
            if(mask<material.renderStyle1.y)discard;
            float coverage=clamp(mask*c.a*material.renderStyle0.x,0.0,1.0);
            vec3 rgb=clamp(c.rgb,vec3(0),vec3(1))*clamp(colorAttenuation,0.0,1.0)
                *mix(1.0,coverage,material.renderStyle0.w);
            if(material.renderStyle2.z>.5)rgb*=coverage;
            c=vec4(rgb,clamp(coverage*material.renderStyle0.y,0.0,1.0));
        }else{c.a*=mask;if(c.a<=.002)discard;}
    }
    outColor=c;
}

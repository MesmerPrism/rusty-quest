#version 430
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable
#ifndef RQ_DEVELOPMENT_FEEDBACK
#define RQ_DEVELOPMENT_FEEDBACK 0
#endif
#include <data/shaders/common.glsl>
#include <data/shaders/app2vertex.glsl>
#include <data/shaders/metaSpatialSdkCommon.glsl>
// transportFlags.z: host geometry0/1; kind2 alone reserves2 for debug barcode draw suppression.
// transportFlags.w: expected generation high16 + GPU-source tag 65536.
// meshFlags: kind, group, expected generation low16, owner rendering enabled.
layout(std140,set=3,binding=0) uniform MaterialUniform {
    vec4 transportFlags; vec4 meshFlags; vec4 renderStyle0; vec4 renderStyle1; vec4 renderStyle2;
} material;
layout(set=3,binding=1) uniform sampler2D atlas;
layout(set=3,binding=2) uniform sampler2D maskAtlas;
layout(location=0) out vec4 workloadColor;
layout(location=1) out vec2 localUv;
layout(location=2) flat out uint flags;
layout(location=3) flat out uint drawable;
layout(location=4) flat out uint particle;
layout(location=5) flat out float colorAttenuation;
vec3 toSrgb(vec3 v) {return mix(12.92*v,1.055*pow(max(v,vec3(0)),vec3(1.0/2.4))-.055,greaterThan(v,vec3(.0031308)));}
uint byteAt(uint b) {
    uint t=b/3u;ivec2 p=ivec2(int(t%1024u),int(t/1024u));
    if(material.transportFlags.y>.5)p.y=1023-p.y;
    vec3 v=texelFetch(atlas,p,0).rgb;if(material.transportFlags.x>.5)v=toSrgb(v);
    uvec3 bytes=uvec3(round(clamp(v,0.0,1.0)*255.0));return bytes[b%3u];
}
uint word(uint b){return byteAt(b)|(byteAt(b+1u)<<8u)|(byteAt(b+2u)<<16u)|(byteAt(b+3u)<<24u);}
uint header(uint w){return word(w*4u);}
bool packetLayoutValid(){
#if RQ_DEVELOPMENT_FEEDBACK
    return header(6u)==0u&&header(7u)==512u&&header(8u)==0u&&header(10u)==512u
        &&header(14u)==0u&&header(16u)==0u&&header(25u)<=512u&&header(26u)==0u&&header(27u)==0u&&header(28u)==0u;
#else
    return header(7u)==2562u&&header(8u)==17934u&&(header(10u)==20496u||header(10u)==20498u)
        &&header(25u)<=2562u&&header(26u)<=17934u;
#endif
}
bool finite3(vec3 v){return !any(isnan(v))&&!any(isinf(v));}
bool handCornerValid(uint record,uint frame,uint generation){
    uint b=(64u+record*8u)*3u;
    vec3 p=vec3(uintBitsToFloat(word(b)),uintBitsToFloat(word(b+4u)),uintBitsToFloat(word(b+8u)));
    return finite3(p)&&all(lessThanEqual(abs(p),vec3(16)))
        &&word(b+16u)==frame&&word(b+20u)==generation&&generation!=0u;
}
vec3 octNormal(uint bits){
    ivec2 v=ivec2(int(bits<<16u)>>16,int(bits)>>16);
    vec2 f=max(vec2(v)/32767.0,vec2(-1));vec3 n=vec3(f,1.0-abs(f.x)-abs(f.y));
    if(n.z<0)n.xy=(1.0-abs(n.yx))*vec2(n.x>=0?1:-1,n.y>=0?1:-1);return normalize(n);
}
void main(){
    App2VertexUnpacked app=getApp2VertexUnpacked();
    uint sourceAndHigh=uint(round(material.transportFlags.w));
    uint expectedGeneration=uint(round(material.meshFlags.z))|((sourceAndHigh&65535u)<<16u);
    bool valid=all(equal(textureSize(atlas,0),ivec2(1024)))&&header(0u)==0x34415752u&&header(1u)==4u
        &&header(2u)==1024u&&header(3u)==1024u&&header(29u)==1u&&header(35u)==1u
        &&packetLayoutValid()&&header(9u)==0u
        &&header(11u)==header(10u)&&header(12u)==header(10u)&&header(13u)==2u*header(10u)
        &&header(14u)<131064u&&header(16u)<131064u&&header(15u)==header(13u)+header(14u)
        &&header(15u)+header(16u)<=131064u&&header(14u)%3u==0u&&header(16u)%3u==0u
        &&header(4u)!=0u
        &&header(40u)==header(15u)+header(16u)&&header(41u)==2u&&header(42u)==header(40u)+2u&&header(43u)==2u
        &&header(42u)+header(43u)<=131064u
        &&header(30u)==floatBitsToUint(2.0)&&header(39u)==floatBitsToUint(16.0)
        &&header(46u)==1u&&header(47u)==header(4u)
        &&expectedGeneration!=0u&&header(4u)==expectedGeneration
        &&((header(6u)&8u)!=0u)==((sourceAndHigh&65536u)!=0u);
    drawable=0u;particle=0u;flags=0u;workloadColor=vec4(1,0,0,1);localUv=vec2(0);colorAttenuation=1.0;
    vec4 world=vec4(app.position,1); // default SDK-uploaded host coordinate path
    if(material.meshFlags.x>1.5){
        uint cell=uint(round(app.uv.x));uint token=cell<32u?header(5u):header(4u);
        bool bit=((token>>(cell%32u))&1u)!=0u;
        workloadColor=valid?(bit?vec4(1,1,1,1):vec4(.03,.03,.03,1)):vec4(1,0,0,1);drawable=1u;if(material.meshFlags.x>2.5)workloadColor=vec4(.35,.35,.35,1);
        if(material.meshFlags.x<2.5&&material.transportFlags.z>1.5)drawable=0u;
        world=g_PrimitiveUniform.worldFromObject*world;
#if RQ_DEVELOPMENT_FEEDBACK
        drawable=0u; // Legacy barcode and occluder are outside the selected public scene.
#endif
    } else {
        ivec2 p=ivec2(round(app.uv*vec2(1024)-.5));int linear=p.y*1024+p.x;
        uint record=uint(max(0,(linear-64)/8));uint b=(64u+record*8u)*3u;
        vec3 raw=vec3(uintBitsToFloat(word(b)),uintBitsToFloat(word(b+4u)),uintBitsToFloat(word(b+8u)));
        vec3 normal=octNormal(word(b+12u));
        valid=valid&&record<131064u&&finite3(raw)&&all(lessThanEqual(abs(raw),vec3(16)));
        // Packet is raw OpenXR RH floor metres. SDK logical basis is +Z forward.
        // adjustPosition applies the SDK HSR renderer convention once, matching the proven probe.
        vec3 sdk=vec3(raw.xy,-raw.z);world=adjustPosition(vec4(sdk,1));
        if(material.meshFlags.x<.5){
            uint rgba=word(b+16u);
            workloadColor=vec4(rgba&255u,(rgba>>8u)&255u,(rgba>>16u)&255u,rgba>>24u)/255.0;
            uint radflags=word(b+20u);flags=radflags>>16u;float radius=float(radflags&65535u)*2.0/65535.0;
            valid=valid&&((flags>>10u)&63u)==(header(5u)&63u)&&(flags&1u)!=0u&&(flags&10u)==0u&&workloadColor.a>.002;
            particle=material.meshFlags.y>2.5?2u:1u;uint index=record-(particle==2u?header(40u):header(9u));bool tracer=(flags&4u)!=0u;
            bool retained=particle==1u&&index>=header(7u)+header(8u);
            valid=valid&&(particle==2u?(record>=header(40u)&&index<2u):(record>=header(9u)&&index<header(10u)
                &&(retained?(!tracer):(tracer?(index>=header(7u)&&index-header(7u)<header(26u)):index<header(25u)))));
            uint auxRecord=(particle==2u?header(42u):header(11u))+index;valid=valid&&auxRecord<131064u&&index<header(12u);
            uint aux=(64u+min(auxRecord,131063u)*8u)*3u;
            float rotation=uintBitsToFloat(word(aux));float anim=uintBitsToFloat(word(aux+4u));
            valid=valid&&word(aux+16u)==header(5u)&&word(aux+20u)==header(4u)
                &&!isnan(rotation)&&!isinf(rotation)&&!isnan(anim)&&!isinf(anim);
            flags=(flags&~1008u)|(uint(floor(clamp(anim,0,.99902344)*63.0+.5))<<4u);
            vec2 corner=app.position.xy;localUv=corner*.5+.5;
            vec2 rotated=mat2(cos(rotation),sin(rotation),-sin(rotation),cos(rotation))*corner;
            mat4 clipFromWorld=getClipFromWorld();
            if(material.meshFlags.w>.5&&particle==1u){
                // The perspective clip-w row is the per-eye optical forward axis;
                // off-centre frustum terms in x/y do not alter it.
                vec3 forward=vec3(clipFromWorld[0][3],clipFromWorld[1][3],clipFromWorld[2][3]);
                float forwardLength=dot(forward,forward);valid=valid&&finite3(forward)&&forwardLength>.000001;
                forward*=inversesqrt(max(forwardLength,.000001));
                float centerDepth=dot(world.xyz-getEyePosition(),forward);
                valid=valid&&centerDepth>.0001;
                vec3 n=adjustPosition(vec4(normal.xy,-normal.z,0)).xyz;
                vec3 viewDirection=getEyePosition()-world.xyz;
                viewDirection*=inversesqrt(max(dot(viewDirection,viewDirection),.000001));
                n*=inversesqrt(max(dot(n,n),.000001));
                float facing=clamp(dot(n,viewDirection),0.0,1.0);
                float depth01=clamp((max(centerDepth,0.0)-material.renderStyle1.z)/material.renderStyle1.w,0.0,1.0);
                float depthAttenuation=exp2(-material.renderStyle0.z*depth01);
                colorAttenuation=depthAttenuation*(1.0-material.renderStyle1.x*(1.0-facing));
            }
            mat4 worldFromClip=inverse(clipFromWorld);
            vec3 right=normalize(worldFromClip[0].xyz),up=normalize(worldFromClip[1].xyz);
            world.xyz+=radius*(right*rotated.x+up*rotated.y);
        }else{
            bool hand=material.meshFlags.y<1.5;uint offset=hand?header(13u):header(15u);
            uint count=hand?header(14u):header(16u);
            valid=valid&&record>=offset&&record-offset<count&&(hand?header(27u):header(28u))!=0u;
            // Every corner rejects the whole raw triplet; flat drawable cannot admit two stale peers.
            uint triangle=offset+((record-offset)/3u)*3u;
            if(valid)valid=handCornerValid(triangle,header(5u),header(4u))
                &&handCornerValid(triangle+1u,header(5u),header(4u))
                &&handCornerValid(triangle+2u,header(5u),header(4u));
            workloadColor.a=1.0;
            vec3 n=adjustPosition(vec4(normal.xy,-normal.z,0)).xyz;
            workloadColor.rgb=(hand?vec3(.12,.85,1):vec3(1,.45,.12));
            workloadColor.rgb*=.5+.5*abs(dot(normalize(n),normalize(getEyePosition()-world.xyz)));
        }
        if(material.transportFlags.z>.5){
            world=g_PrimitiveUniform.worldFromObject*adjustPosition(vec4(app.position.xy*.025+vec2(0,1.4),1.5,1));
            // Deliberate static-host negative; only active records remain shown.
        }
        drawable=valid?1u:0u;
        if(!valid)world=adjustPosition(vec4(0,1.4,1.5,1)); // finite clipped diagnostics, no NaN rasterization
    }
    gl_Position=getClipFromWorld()*world;postprocessPosition(gl_Position);
}

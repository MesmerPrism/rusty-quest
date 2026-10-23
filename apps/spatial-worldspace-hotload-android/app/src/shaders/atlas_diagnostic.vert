#version 430
#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable
#include <data/shaders/common.glsl>
#include <data/shaders/app2vertex.glsl>
#include <data/shaders/metaSpatialSdkCommon.glsl>
// Independent diagnostic material: restore, flip, page, revision-low8.
// publicCohort: producer generation low/high16, admitted public primary offset, enabled.
layout(std140,set=3,binding=0) uniform MaterialUniform { vec4 diagnosticFlags; vec4 publicCohort; } material;
layout(set=3,binding=1) uniform sampler2D atlas;
layout(location=0) out vec4 diagnosticColor;
vec3 toSrgb(vec3 v){return mix(12.92*v,1.055*pow(max(v,vec3(0)),vec3(1.0/2.4))-.055,greaterThan(v,vec3(.0031308)));}
vec3 sampleAt(ivec2 p,out bool available){
    ivec2 dimensions=textureSize(atlas,0);
    available=all(greaterThanEqual(p,ivec2(0)))&&all(lessThan(p,dimensions));
    if(!available)return vec3(0); // Never issue an out-of-bounds texelFetch.
    return texelFetch(atlas,p,0).rgb;
}
vec3 sampleTexel(uint texel,out bool available){
    ivec2 p=ivec2(int(texel%1024u),int(texel/1024u));
    if(material.diagnosticFlags.y>.5)p.y=1023-p.y;
    return sampleAt(p,available);
}
uint byteAt(uint b,out bool available){
    vec3 rgb=sampleTexel(b/3u,available);
    if(material.diagnosticFlags.x>.5)rgb=toSrgb(rgb);
    return uvec3(round(clamp(rgb,0.0,1.0)*255.0))[b%3u];
}
uint dataWord(uint offset,out bool available){
    bool a,b,c,d;
    uint value=byteAt(offset,a)|(byteAt(offset+1u,b)<<8u)|(byteAt(offset+2u,c)<<16u)|(byteAt(offset+3u,d)<<24u);
    available=a&&b&&c&&d;return value;
}
uint header(uint w,out bool available){return dataWord(w*4u,available);}
uint payloadToken(uint row,out bool available){
    uint endpoint=(row-44u)/2u,section=endpoint/2u;
    uint offsetIndex=section==0u?11u:section==1u?13u:15u;
    bool a,b;uint offset=header(offsetIndex,a),count=header(offsetIndex+1u,b);
    available=a&&b&&count>0u&&offset<131064u&&count<=131064u-offset;
    if(!available)return 0u;
    uint record=offset+(endpoint%2u==0u?0u:count-1u);
    return dataWord((64u+record*8u)*3u+16u+((row-44u)%2u)*4u,available);
}
bool predicateAvailable(uint index,bool a[48]){
    switch(index){
        case 0u:return true;
        case 1u:return a[0];case 2u:return a[1];case 3u:return a[2];case 4u:return a[3];
        case 5u:return a[29];case 6u:return a[35];case 7u:return a[7];case 8u:return a[8];
        case 9u:return a[9];case 10u:return a[10];case 11u:return a[11]&&a[10];case 12u:return a[12]&&a[10];
        case 13u:return a[13]&&a[10];case 14u:return a[14];case 15u:return a[16];
        case 16u:return a[15]&&a[13]&&a[14];case 17u:return a[15]&&a[16];
        case 18u:return a[14];case 19u:return a[16];case 20u:return a[25];case 21u:return a[26];
        case 22u:return a[4];case 23u:return a[40]&&a[15]&&a[16];case 24u:return a[41];
        case 25u:return a[42]&&a[40];case 26u:return a[43];case 27u:return a[30];case 28u:return a[39];
        case 29u:return a[46];case 30u:return a[47]&&a[4];
    }
    return false;
}
bool predicate(uint index,uint h[48],ivec2 dimensions){
    switch(index){
        case 0u:return all(equal(dimensions,ivec2(1024)));
        case 1u:return h[0]==0x34415752u;
        case 2u:return h[1]==4u;
        case 3u:return h[2]==1024u;
        case 4u:return h[3]==1024u;
        case 5u:return h[29]==1u;
        case 6u:return h[35]==1u;
        case 7u:return h[7]==2562u;
        case 8u:return h[8]==17934u;
        case 9u:return h[9]==0u;
        case 10u:return h[10]==20496u||h[10]==20498u;
        case 11u:return h[11]==h[10];
        case 12u:return h[12]==h[10];
        case 13u:return h[13]==h[10]+h[10];
        case 14u:return h[14]<131064u;
        case 15u:return h[16]<131064u;
        case 16u:return h[15]==h[13]+h[14];
        case 17u:return h[15]+h[16]<=131064u;
        case 18u:return h[14]%3u==0u;
        case 19u:return h[16]%3u==0u;
        case 20u:return h[25]<=2562u;
        case 21u:return h[26]<=17934u;
        case 22u:return h[4]!=0u;
        case 23u:return h[40]==h[15]+h[16];
        case 24u:return h[41]==2u;
        case 25u:return h[42]==h[40]+2u;
        case 26u:return h[43]==2u;
        case 27u:return h[30]==floatBitsToUint(2.0);
        case 28u:return h[39]==floatBitsToUint(16.0);
        case 29u:return h[46]==1u;
        case 30u:return h[47]==h[4];
    }
    return false;
}
bool publicPacket(out uint h[48]){
    uint expected=uint(round(material.publicCohort.x))|(uint(round(material.publicCohort.y))<<16u);
    if(material.publicCohort.w<.5||expected==0u)return false;
    for(uint i=0u;i<48u;i++){bool available;h[i]=header(i,available);if(!available)return false;}
    for(uint i=0u;i<31u;i++)if(!predicate(i,h,textureSize(atlas,0)))return false;
    // Bind to the live producer AND admitted manifest, not merely self-consistent offsets.
    return h[4]==expected&&(h[6]&8u)!=0u&&h[40]==uint(round(material.publicCohort.z))
        &&h[40]<=131060u&&h[42]==h[40]+2u&&h[44]!=0u&&h[45]<=2u;
}
uint publicWordOffset(uint page,uint slot,out bool available){
    uint h[48];available=publicPacket(h);if(!available)return 0u;
    if(page==6u)return 192u+24u*(h[42]+slot/4u)+4u*(slot%4u);
    if(slot<6u)return 192u+24u*(h[40]+slot/3u)+4u*(slot%3u);
    return 4u*(44u+slot-6u);
}
void main(){
    App2VertexUnpacked app=getApp2VertexUnpacked();
    uint column=uint(round(app.uv.x)),row=uint(round(app.uv.y));
    if(row>=100u&&row<104u){
        // Fixed planar fiducials never sample the packet or use its validity.
        vec3 colors[4]=vec3[4](vec3(0,1,1),vec3(1,1,0),vec3(1,.4,0),vec3(.65,0,1));
        diagnosticColor=vec4(column==0u?vec3(1):column==1u?vec3(0):colors[row-100u],1);
        vec4 world=g_PrimitiveUniform.worldFromObject*vec4(app.position,1);
        gl_Position=getClipFromWorld()*world;postprocessPosition(gl_Position);return;
    }
    ivec2 dimensions=textureSize(atlas,0);uint page=uint(clamp(round(material.diagnosticFlags.z),0.0,7.0));
    // Anchors and dimension bits do not read packet bytes or use packet validity.
    uint value=0u;bool bit=false,available=true;
    if(row==0u){
        uint profile=(material.diagnosticFlags.x>.5?1u:0u)|(material.diagnosticFlags.y>.5?2u:0u);
        uint revision=uint(clamp(round(material.diagnosticFlags.w),0.0,255.0));
        if(column<8u)bit=true;
        else if(column<16u)bit=false;
        else if(column<19u)bit=((page>>(column-16u))&1u)!=0u;
        else if(column<21u)bit=((profile>>(column-19u))&1u)!=0u;
        else if(column<29u)bit=((revision>>(column-21u))&1u)!=0u;
        else bit=column!=30u; // Fixed orientation guards: white, black, white.
    }
    else if(row==1u||row==2u){value=uint(max(0,row==1u?dimensions.x:dimensions.y));bit=((value>>column)&1u)!=0u;}
    else if(row==3u){
        uint h[48];bool a[48];
        for(uint i=0u;i<48u;i++)h[i]=header(i,a[i]);
        available=predicateAvailable(column,a);
        if(column<31u){bit=predicate(column,h,dimensions);diagnosticColor=available?(bit?vec4(0,1,0,1):vec4(1,0,0,1)):vec4(1,0,1,1);}
        else diagnosticColor=vec4(0,.2,1,1);
    }else if(row<=16u){
        if(page>=6u&&row<12u){
            uint offset=publicWordOffset(page,row-4u,available);
            if(available)value=dataWord(offset,available);
        }else{
            uint wordIndex=row<12u?page*8u+(row-4u):(row==12u?4u:row==13u?5u:row==14u?35u:row==15u?17u:47u);
            value=header(wordIndex,available);
        }
        bit=((value>>column)&1u)!=0u;
    }else if(row<=19u){
        // Raw sampled RGB, quantized to 8 bits, of the first texel touched by
        // this page's first word. No restore transform is applied to these rows.
        uint offset=page*8u*4u;
        if(page>=6u)offset=publicWordOffset(page,0u,available);
        vec3 rgb=vec3(0);if(available)rgb=sampleTexel(offset/3u,available);
        uint channel=min(row-17u,2u);value=uvec3(round(clamp(rgb,0.0,1.0)*255.0))[channel];
        bit=column<8u&&((value>>column)&1u)!=0u;
    }else if(row>=44u&&row<=55u){
        // Payload reads: particle AUX is atlas publication, hand/graft is GPU pack provenance.
        value=payloadToken(row,available);bit=((value>>column)&1u)!=0u;
    }else{
        // Physical texture coordinates, independent of diagnostic restore/flip.
        // Corners can be background; they are observations, not producer anchors.
        uint channel=(row-20u)%3u;ivec2 p;
        if(row<32u){
            uint corner=(row-20u)/3u;
            p=ivec2((corner==1u||corner==3u)?dimensions.x-1:0,corner>=2u?dimensions.y-1:0);
        }else p=ivec2(int((row-32u)/3u),0); // Header physical texels 0..3.
        vec3 rgb=sampleAt(p,available);value=uvec3(round(clamp(rgb,0.0,1.0)*255.0))[channel];
        bit=column<8u&&((value>>column)&1u)!=0u;
    }
    if(row!=3u)diagnosticColor=available?(bit?vec4(1):vec4(.005,.005,.005,1)):vec4(1,0,1,1);
    // Fixed SDK host geometry, independent of atlas position/radius/validity.
    vec4 world=g_PrimitiveUniform.worldFromObject*vec4(app.position,1);
    gl_Position=getClipFromWorld()*world;postprocessPosition(gl_Position);
}

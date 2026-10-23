#version 450
layout(std430,set=0,binding=0) readonly buffer Particles { vec4 rows[]; } particles;
layout(std430,set=0,binding=1) readonly buffer Hands { vec4 rows[]; } hands;
layout(std430,set=0,binding=2) readonly buffer Header { uint words[48]; } header;
layout(std430,set=0,binding=3) readonly buffer Diagnostic { vec4 rows[]; } diagnostic;
layout(location=0) out vec4 outColor;
uint byteFrom(uint word,uint byteIndex){return(word>>(byteIndex*8u))&255u;}
bool finite3(vec3 p){return !any(isnan(p))&&!any(isinf(p));}
uint octNormal(vec3 n){
    if(!finite3(n)||dot(n,n)<1e-12)n=vec3(0,1,0);
    n/=abs(n.x)+abs(n.y)+abs(n.z);
    vec2 p=n.xy;if(n.z<0)p=(1.0-abs(p.yx))*mix(vec2(-1),vec2(1),greaterThanEqual(p,vec2(0)));
    ivec2 v=ivec2(round(clamp(p,-1.0,1.0)*32767.0));
    return(uint(v.x)&65535u)|((uint(v.y)&65535u)<<16u);
}
uint recordWord(uint record,uint word){
    uint count=header.words[10],auxOffset=header.words[11],handOffset=header.words[13],graftOffset=header.words[15];
    uint total=header.words[42]+header.words[43];
    if(record>=total||header.words[35]==0u)return 0u;
    if(record>=header.words[42]&&record<header.words[42]+header.words[43]){
        uint i=record-header.words[42];
        if(word<4u)return floatBitsToUint(diagnostic.rows[i*4u+3u][word]);
        return word==4u?header.words[5]:header.words[4];
    }
    if(record>=auxOffset&&record<auxOffset+header.words[12]){
        uint i=record-auxOffset;
        if(word<4u)return floatBitsToUint(particles.rows[i*4u+3u][word]);
        return word==4u?header.words[5]:header.words[4];
    }
    if(record>=handOffset&&record<graftOffset+header.words[16]){
        uint i=record-handOffset;vec4 p=hands.rows[i*2u],n=hands.rows[i*2u+1u];
        bool invalid=!finite3(p.xyz)||any(greaterThan(abs(p.xyz),vec3(uintBitsToFloat(header.words[39]))));
        if(word<3u)return floatBitsToUint(invalid?0.0:p[word]);
        if(word==3u)return octNormal(n.xyz);
        // RWA4 hand records preserve the actual pack dispatch's tokens.
        if(word==4u)return floatBitsToUint(n.w);
        return invalid?0u:floatBitsToUint(p.w);
    }
    vec3 position=vec3(0),normal=vec3(0,1,0);vec4 color=vec4(1);float radius=0;bool enabled=false;uint flags=0;
    if(record<count){
        uint i=record;vec4 p=particles.rows[i*4u],n=particles.rows[i*4u+2u],aux=particles.rows[i*4u+3u];
        position=p.xyz;radius=p.w;normal=n.xyz;color=particles.rows[i*4u+1u];
        bool retained=i>=header.words[7]+header.words[8];
        bool tracer=i>=header.words[7]&&!retained;
        enabled=n.w>0.0&&(retained||(tracer?(i-header.words[7]<header.words[26]):i<header.words[25]));
        flags=(tracer?4u:0u)|(uint(round(clamp(aux.y,0,1)*63.0))<<4u);
    }else if(record>=header.words[40]&&record<header.words[40]+header.words[41]){
        uint i=record-header.words[40];vec4 p=diagnostic.rows[i*4u],n=diagnostic.rows[i*4u+2u];
        position=p.xyz;radius=p.w;normal=n.xyz;color=diagnostic.rows[i*4u+1u];enabled=n.w>0;
    }
    bool invalid=!finite3(position)||any(greaterThan(abs(position),vec3(uintBitsToFloat(header.words[39]))))||isnan(radius)||isinf(radius)||radius<0||radius>uintBitsToFloat(header.words[30]);
    if(invalid){flags|=2u;enabled=false;position=vec3(0);radius=0;}
    flags|=(enabled?1u:0u)|((header.words[5]&63u)<<10u);
    if(word<3u)return floatBitsToUint(position[word]);
    if(word==3u)return octNormal(normal);
    if(word==4u){if(any(isnan(color))||any(isinf(color)))color=vec4(0);uvec4 c=uvec4(round(clamp(color,0,1)*255));return c.x|(c.y<<8u)|(c.z<<16u)|(c.w<<24u);}
    uint r=uint(round(clamp(radius/uintBitsToFloat(header.words[30]),0,1)*65535));return r|(flags<<16u);
}
uint packetByte(uint offset){
    if(offset<192u)return byteFrom(header.words[offset/4u],offset%4u);
    uint body=offset-192u,record=body/24u,b=body%24u;
    return byteFrom(recordWord(record,b/4u),b%4u);
}
void main(){
    uint pixel=uint(gl_FragCoord.y)*1024u+uint(gl_FragCoord.x),offset=pixel*3u;
    outColor=vec4(vec3(packetByte(offset),packetByte(offset+1u),packetByte(offset+2u))/255.0,1.0);
}

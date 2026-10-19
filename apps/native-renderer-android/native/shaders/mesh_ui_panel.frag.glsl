#version 450
layout(set=0,binding=0) uniform sampler2D panel;
layout(location=0) in vec2 uv;
layout(location=0) out vec4 color;
void main() {
    vec2 dx=dFdx(uv),dy=dFdy(uv);
    vec2 texels=vec2(textureSize(panel,0));
    float footprint=max(length(dx*texels),length(dy*texels));
    if(footprint<=1.0) {
        color=textureLod(panel,uv,0.0);
    } else {
        // Integrate the screen-pixel parallelogram on the immutable single-mip AHB.
        // Four samples per axis target <=4 source texels/pixel; steeper minification
        // remains bounded work rather than claiming arbitrary-angle reconstruction.
        vec4 sum=vec4(0.0);
        for(int y=0;y<4;++y) for(int x=0;x<4;++x) {
            vec2 offset=(vec2(x,y)+vec2(0.5))*0.25-vec2(0.5);
            sum+=textureLod(panel,uv+dx*offset.x+dy*offset.y,0.0);
        }
        color=sum*(1.0/16.0);
    }
    // Preserve the existing opaque-backing coverage test.
    if(color.a < .5) discard;
}

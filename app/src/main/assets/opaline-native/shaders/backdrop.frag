#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uArtwork;
uniform vec2 uCrop;
uniform vec3 uTint;
uniform float uTime, uDim;
// The transmission receiver is an sRGB texture: this pass writes linear light into it, which the
// GPU encodes on write and decodes on read, as three.js fills its transmission render target.
uniform bool uLinearOutput;

vec3 sRGBTransferEOTF(in vec3 value) {
    return mix(pow(value * 0.9478672986 + vec3(0.0521327014), vec3(2.4)), value * 0.0773993808,
        vec3(lessThanEqual(value, vec3(0.04045))));
}

void main(){
    vec2 uv=(vUv-.5)*uCrop+.5;
    vec3 art=texture(uArtwork,vec2(uv.x,1.-uv.y)).rgb;
    float veil=smoothstep(.1,.8,vUv.y)*.08;
    // Artwork is a distant plane. The foreground environment is actual GLB geometry.
    vec3 color=mix(art*.48,uTint,.24+veil);
    color*=1.-uDim*.72;
    fragColor=vec4(uLinearOutput ? sRGBTransferEOTF(color) : color,1.);
}

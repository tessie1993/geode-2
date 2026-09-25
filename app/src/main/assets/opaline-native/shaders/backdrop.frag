#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uArtwork;
uniform vec2 uCrop;
uniform vec3 uTint;
uniform float uTime, uDim;
void main(){
    vec2 uv=(vUv-.5)*uCrop+.5;
    vec3 art=texture(uArtwork,vec2(uv.x,1.-uv.y)).rgb;
    float veil=smoothstep(.1,.8,vUv.y)*.08;
    // Artwork is a distant plane. The foreground environment is actual GLB geometry.
    vec3 color=mix(art*.48,uTint,.24+veil);
    color*=1.-uDim*.72;
    fragColor=vec4(color,1.);
}

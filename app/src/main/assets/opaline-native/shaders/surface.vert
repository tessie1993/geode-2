#version 300 es
precision highp float;
layout(location=0) in vec3 aPosition;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec3 aMorphLow;
layout(location=3) in vec3 aMorphHigh;
uniform mat4 uModel, uProjection;
uniform mat3 uNormal;
uniform vec3 uContact;
uniform vec2 uMorph;
uniform float uPressure, uDeform, uTime, uReveal;
out vec3 vPosition, vNormal, vLocal;
void main() {
    vec3 p=aPosition + mix(aMorphLow,aMorphHigh,uMorph.x)*uMorph.y;
    vec3 d=p-uContact;
    float force=uPressure*uDeform*exp(-(dot(d.xy,d.xy)+d.z*d.z*.3)/.72);
    p.xy+=d.xy*force*.07;
    p.z-=force*.12;
    // L06 contour construction: the rim arrives before the quiet interior.
    float front=smoothstep(0.0,.72,uReveal);
    p.z*=mix(.12,1.0,front);
    vec3 n=normalize(aNormal+vec3(-d.xy*force*.33,0));
    vec4 world=uModel*vec4(p,1);
    vPosition=world.xyz; vNormal=normalize(uNormal*n); vLocal=p;
    gl_Position=uProjection*world;
}

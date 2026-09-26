#version 300 es
precision highp float;

// The medium of createVolumeCausticsMaterial (src/optics.js): a box of the PhotonVolume's world
// bounds at their centre (docs/OPTICS.md), here the unit cube mapped onto those bounds.
layout(location = 0) in vec3 aPosition;

uniform mat4 uView, uProjection;
uniform vec3 uBoundsMin, uBoundsMax;

out vec3 vPhotonWorld;

void main() {
    vPhotonWorld = mix(uBoundsMin, uBoundsMax, aPosition * 0.5 + 0.5);
    gl_Position = uProjection * uView * vec4(vPhotonWorld, 1.0);
}

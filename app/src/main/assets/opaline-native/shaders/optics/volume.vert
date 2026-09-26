#version 300 es
precision highp float;

// VOLUME_VERTEX of src/shaders/opaline.js on the medium's box. createVolumeMaterial goes on a box
// of half-extents `bounds` (the workbench builds BoxGeometry(bounds * 2)), so the unit cube
// scales by uBounds into the local coordinates the fragment integrates in.
layout(location = 0) in vec3 aPosition;

uniform mat4 uModelView, uProjection;
uniform vec3 uBounds;

out vec3 vOpLocal;

void main() {
    vOpLocal = aPosition * uBounds;
    gl_Position = uProjection * uModelView * vec4(vOpLocal, 1.0);
}

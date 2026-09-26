#version 300 es
precision highp float;

// The unlit realm surfaces of src/workbench.js: the scene background (a full-screen triangle), the
// distant backdrop plate, the Reflector plane, the mist planes and particle trails.
layout(location = 0) in vec3 aPosition;

uniform int uMode; // 0 background, 1 plate, 2 reflector, 3 mist, 4 particle trail lines
uniform mat4 uModel, uProjection, uTextureMatrix;

out vec3 vLocal;
out vec4 vUv;
out float vFogDepth;

void main() {
    if (uMode == 0) {
        vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
        vLocal = vec3(p, 0.0);
        vUv = vec4(0.0);
        vFogDepth = 0.0;
        gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        return;
    }
    vec4 mvPosition = uModel * vec4(aPosition, 1.0);
    vLocal = aPosition;
    vUv = uTextureMatrix * vec4(aPosition, 1.0);
    vFogDepth = -mvPosition.z;
    gl_Position = uProjection * mvPosition;
}

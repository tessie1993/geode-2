#version 300 es
precision highp float;

// Pass.js FullScreenQuad: the triangle (-1, 3), (-1, -1), (3, -1) with uv = (position + 1) / 2,
// generated from gl_VertexID so the post passes need no vertex buffer.
out vec2 vUv;

void main() {
    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}

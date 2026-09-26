#version 300 es
precision highp float;

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec3 aMorphLow;
layout(location = 3) in vec3 aMorphHigh;
// enableFilmThickness: BubbleFilm thickness in metres (0 where no film stream is bound).
layout(location = 4) in float aFilmThickness;

uniform mat4 uModel, uProjection; // uModel is model-view: the camera sits at the origin.
uniform mat3 uNormal;
uniform vec3 uContact;
uniform vec2 uMorph;
uniform float uPressure, uDeform;

// shadowmap_vertex: the position offset along the normal in the frame uShadowModel maps into,
// then carried by uShadowMatrix into the sun's shadow map.
uniform mat4 uShadowModel, uShadowMatrix;
uniform float uShadowNormalBias;

// src/workbench.js water: rest positions of the 70 m, 180-segment plane (spacing uWaterStep, half
// extent uWaterHalf) lifted by three sines and up to eight touch ripples (x, z, start, strength).
// Normals are computeVertexNormals of the same grid as of every third frame (uWaterNormalTime,
// uNormalRipples).
uniform bool uWater;
uniform float uWaterTime, uWaterNormalTime, uWaterStep, uWaterHalf;
uniform vec4 uRipples[8];
uniform vec4 uNormalRipples[8];

out vec3 vViewPosition;
out vec3 vNormal;
out vec3 vOpPosition;
out vec3 vWorldPosition;
out vec4 vShadowCoord;
out float vOpFilmThickness;

float waterHeight(vec2 p, float t, vec4 ripples[8]) {
    float h = sin(p.x * .48 + t * .5) * .029 + cos(p.y * .61 + t * .35) * .024 +
        sin((p.x + p.y) * 1.4 + t * .7) * .011;
    for (int i = 0; i < 8; i++) {
        float age = t - ripples[i].z;
        float front = distance(p, ripples[i].xy) - age * 1.6;
        if (age < 8.0) h += cos(front * 7.0) * exp(-front * front * 1.3) * exp(-age * .65) * .075 * ripples[i].w;
    }
    return h;
}

vec3 waterPoint(vec2 p) {
    return vec3(p.x, waterHeight(p, uWaterNormalTime, uNormalRipples), p.y);
}

// PlaneGeometry splits each quad along (ix, iy + 1)-(ix + 1, iy); every face (A, B, C) around the
// vertex adds cross(C - B, A - B), as BufferGeometry.computeVertexNormals does.
vec3 waterNormal(vec2 p) {
    float s = uWaterStep;
    float edge = uWaterHalf - s * .5;
    vec3 v = waterPoint(p);
    vec3 e = waterPoint(p + vec2(s, 0.0));
    vec3 w = waterPoint(p - vec2(s, 0.0));
    vec3 n = waterPoint(p + vec2(0.0, s));
    vec3 so = waterPoint(p - vec2(0.0, s));
    vec3 nw = waterPoint(p + vec2(-s, s));
    vec3 se = waterPoint(p + vec2(s, -s));
    vec3 sum = vec3(0.0);
    if (p.x < edge && p.y < edge) sum += cross(e - n, v - n);
    if (p.x < edge && p.y > -edge) sum += cross(se - v, so - v) + cross(se - e, v - e);
    if (p.x > -edge && p.y > -edge) sum += cross(so - v, w - v);
    if (p.x > -edge && p.y < edge) sum += cross(v - nw, w - nw) + cross(v - n, nw - n);
    return normalize(sum);
}

void main() {
    vec3 position = aPosition;
    vec3 objectNormal = aNormal;
    if (uWater) {
        position.y = waterHeight(position.xz, uWaterTime, uRipples);
        objectNormal = waterNormal(position.xz);
    }

    // MotionController.update writes the press into the position buffer, so the pressed vertex
    // is also the object-space position the surface hooks read (vOpPosition = position).
    vec3 d = position - uContact;
    float force = uPressure * uDeform * exp(-(dot(d.xy, d.xy) + d.z * d.z * .3) / .72);
    vec3 pressed = position + vec3(d.xy * force * .07, -force * .12);

    // computeVertexNormals() after the press: the rest normal carried through the inverse
    // transpose of the displacement's Jacobian.
    vec3 gradient = force * vec3(-2.0 * d.x, -2.0 * d.y, -.6 * d.z) / .72;
    mat3 jacobian = mat3(
        vec3(1.0 + .07 * (force + d.x * gradient.x), .07 * d.y * gradient.x, -.12 * gradient.x),
        vec3(.07 * d.x * gradient.y, 1.0 + .07 * (force + d.y * gradient.y), -.12 * gradient.y),
        vec3(.07 * d.x * gradient.z, .07 * d.y * gradient.z, 1.0 - .12 * gradient.z)
    );
    vec3 normal = normalize(transpose(inverse(jacobian)) * objectNormal);

    // Liquid-rail morph targets blend between the two nearest authored shapes.
    vec3 transformed = pressed + mix(aMorphLow, aMorphHigh, uMorph.x) * uMorph.y;

    vec4 mvPosition = uModel * vec4(transformed, 1.0);
    vViewPosition = -mvPosition.xyz;
    vWorldPosition = mvPosition.xyz;
    vNormal = normalize(uNormal * normal);
    vOpPosition = pressed;
    vOpFilmThickness = aFilmThickness;
    vec4 shadowPosition = uShadowModel * vec4(transformed, 1.0);
    vec3 shadowNormal = normalize(transpose(inverse(mat3(uShadowModel))) * normal);
    vShadowCoord = uShadowMatrix * vec4(shadowPosition.xyz + shadowNormal * uShadowNormalBias, 1.0);
    gl_Position = uProjection * mvPosition;
}

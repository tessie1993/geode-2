#version 300 es
precision highp float;

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec3 aMorphLow;
layout(location = 3) in vec3 aMorphHigh;

uniform mat4 uModel, uProjection; // uModel is model-view: the camera sits at the origin.
uniform mat3 uNormal;
uniform vec3 uContact, uShapeCenter, uShapeHalf;
uniform vec2 uMorph;
uniform float uPressure, uDeform, uReveal;

out vec3 vViewPosition;
out vec3 vNormal;
out vec3 vOpPosition;
out vec3 vWorldPosition;

// L06 panel contour reveal from src/transitions.js: the outline forms first, the interior follows,
// and geometry settles at 72% of the transition. The collapsed source is the same shell pressed
// to 12% of its depth, so the reveal needs no second mesh.
vec3 reveal(vec3 p) {
    if (uReveal >= 1.0) return p;
    vec2 q = (p.xy - uShapeCenter.xy) / max(uShapeHalf.xy, vec2(.001));
    float edge = clamp(max(abs(q.x), abs(q.y)), 0.0, 1.0);
    float x = clamp((uReveal / .72 - .26 * (1.0 - edge)) / .74, 0.0, 1.0);
    float blend = x * x * x * (x * (x * 6.0 - 15.0) + 10.0);
    vec3 source = vec3(p.xy, uShapeCenter.z + (p.z - uShapeCenter.z) * .12);
    return mix(source, p, blend);
}

void main() {
    // MotionController.update writes the press into the position buffer, so the pressed vertex
    // is also the object-space position the surface hooks read (vOpPosition = position).
    vec3 d = aPosition - uContact;
    float force = uPressure * uDeform * exp(-(dot(d.xy, d.xy) + d.z * d.z * .3) / .72);
    vec3 pressed = aPosition + vec3(d.xy * force * .07, -force * .12);

    // computeVertexNormals() after the press: the rest normal carried through the inverse
    // transpose of the displacement's Jacobian.
    vec3 gradient = force * vec3(-2.0 * d.x, -2.0 * d.y, -.6 * d.z) / .72;
    mat3 jacobian = mat3(
        vec3(1.0 + .07 * (force + d.x * gradient.x), .07 * d.y * gradient.x, -.12 * gradient.x),
        vec3(.07 * d.x * gradient.y, 1.0 + .07 * (force + d.y * gradient.y), -.12 * gradient.y),
        vec3(.07 * d.x * gradient.z, .07 * d.y * gradient.z, 1.0 - .12 * gradient.z)
    );
    vec3 normal = normalize(transpose(inverse(jacobian)) * aNormal);

    // Liquid-rail morph targets blend between the two nearest authored shapes.
    vec3 transformed = reveal(pressed + mix(aMorphLow, aMorphHigh, uMorph.x) * uMorph.y);

    vec4 mvPosition = uModel * vec4(transformed, 1.0);
    vViewPosition = -mvPosition.xyz;
    vWorldPosition = mvPosition.xyz;
    vNormal = normalize(uNormal * normal);
    vOpPosition = pressed;
    gl_Position = uProjection * mvPosition;
}

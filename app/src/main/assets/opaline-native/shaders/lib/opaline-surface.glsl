// Opaline surface hooks, ported from ui-system/Opaline-3D-Library/src/shaders/opaline.js. The
// library injects these strings into MeshPhysicalMaterial at <color_fragment>,
// <roughnessmap_fragment>, <emissivemap_fragment> and <lights_physical_fragment>; the surface pass
// calls them at the same four points. All fields use object-space position (vOpPosition).

uniform float uOpTime, uOpFlow, uOpCloud, uOpGrain, uOpExcitation, uOpRadius, uOpFilm;
uniform vec3 uOpAccent, uOpDeep, uOpTouch, uOpVelocity;

// --- NOISE_3D ---
float opHash(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

float opNoise(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(opHash(i), opHash(i + vec3(1, 0, 0)), f.x),
                   mix(opHash(i + vec3(0, 1, 0)), opHash(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(opHash(i + vec3(0, 0, 1)), opHash(i + vec3(1, 0, 1)), f.x),
                   mix(opHash(i + vec3(0, 1, 1)), opHash(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}

float opFbm(vec3 p) {
    float f = 0.0, a = 0.5;
    mat3 r = mat3(0.00, 0.80, 0.60, -0.80, 0.36, -0.48, -0.60, -0.48, 0.64);
    for (int i = 0; i < 5; i++) {
        f += a * opNoise(p);
        p = r * p * 2.03 + vec3(4.7, 1.3, 7.9);
        a *= 0.5;
    }
    return f;
}

// Analytic divergence-free cyclic field. It advects visual density coordinates, not conserved
// pigment; simulation-backed pigment must supply its own density.
vec3 opFlow(vec3 p, float t) {
    return vec3(sin(p.y * 1.13 + t * .17) + cos(p.z * .97 - t * .13),
                sin(p.z * 1.07 + t * .11) + cos(p.x * 1.21 + t * .09),
                sin(p.x * .91 - t * .15) + cos(p.y * 1.03 + t * .12));
}

// --- SURFACE_COLOR: returns the cloud value SURFACE_FILM reuses ---
float opSurfaceColor(const in vec3 position, inout vec3 diffuse) {
    vec3 opP = position * 2.4;
    vec3 opAdvected = opP + uOpFlow * opFlow(opP, uOpTime) * 0.22;
    float opCloud = opFbm(opAdvected + opFbm(opAdvected * 0.7) * 1.6);
    float opVein = smoothstep(0.42, 0.68, opCloud);
    vec3 opTint = mix(uOpDeep, uOpAccent, opVein);
    diffuse = mix(diffuse, diffuse * opTint * 1.55, uOpCloud);
    return opCloud;
}

// --- SURFACE_ROUGHNESS ---
float opSurfaceRoughness(const in vec3 position, const in float roughnessFactor) {
    return clamp(roughnessFactor + (opNoise(position * 42.0) - 0.5) * uOpGrain, 0.018, 1.0);
}

// --- SURFACE_EMISSION ---
vec3 opSurfaceEmission(const in vec3 position) {
    vec3 opTouchDelta = position - uOpTouch;
    float opContact = exp(-dot(opTouchDelta, opTouchDelta) / max(0.003, uOpRadius * uOpRadius));
    float opTrail = exp(-length(opTouchDelta + uOpVelocity * .035) * 8.0);
    return uOpAccent * uOpExcitation * (opContact * .42 + opTrail * .06);
}

// --- SURFACE_FILM ---
float opSurfaceFilm(const in vec3 position, const in float cloud, const in float thickness,
    const in float thicknessMinimum, const in float thicknessMaximum) {
    float opFilmField = clamp(cloud * .70 + (1.0 - smoothstep(-1.0, 1.0, position.y)) * .30, 0.0, 1.0);
    float opFilmThickness = mix(thicknessMinimum, thicknessMaximum, opFilmField);
    return mix(thickness, opFilmThickness, uOpFilm);
}

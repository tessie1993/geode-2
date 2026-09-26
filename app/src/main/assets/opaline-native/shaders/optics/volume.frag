#version 300 es
precision highp float;

// VOLUME_FRAGMENT of src/shaders/opaline.js (src/materials.js createVolumeMaterial): a bounded
// participating medium with 96 view samples, 8 light samples, Beer-Lambert extinction and
// Henyey-Greenstein single scattering; the density boundary is an ellipsoid (uShape 0) or a box
// (uShape 1) of half-extents uBounds in local coordinates. Rendered into the linear HDR target,
// where three.js applies no tone mapping or colour-space conversion.
in vec3 vOpLocal;
out vec4 fragColor;

// uWorldToLocal * cameraPosition.
uniform vec3 uCameraLocal;
uniform vec3 uBounds, uScatterColor, uAbsorption, uLightPosition, uLightColor, uAmbient;
uniform float uDensity, uTime, uAnisotropy, uShape, uExcitation;
uniform vec3 uTouch;

// NOISE_3D
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

vec3 opFlow(vec3 p, float t) {
    return vec3(sin(p.y * 1.13 + t * .17) + cos(p.z * .97 - t * .13),
                sin(p.z * 1.07 + t * .11) + cos(p.x * 1.21 + t * .09),
                sin(p.x * .91 - t * .15) + cos(p.y * 1.03 + t * .12));
}

vec2 opBoxInterval(vec3 o, vec3 d) {
    vec3 inv = 1.0 / (d + vec3(0.0000001));
    vec3 a = (-uBounds - o) * inv, b = (uBounds - o) * inv;
    vec3 lo = min(a, b), hi = max(a, b);
    return vec2(max(max(lo.x, lo.y), lo.z), min(min(hi.x, hi.y), hi.z));
}

float opDensity(vec3 p) {
    vec3 q = p / uBounds;
    float edge = uShape < 0.5 ? length(q) : max(max(abs(q.x), abs(q.y)), abs(q.z));
    float mask = 1.0 - smoothstep(0.82, 0.995, edge);
    vec3 flow = p * 2.3 + opFlow(p * 1.4, uTime) * 0.13;
    return uDensity * mask * (0.42 + 0.85 * opFbm(flow));
}

float opHG(float mu) {
    float g = uAnisotropy;
    return (1.0 - g * g) / (12.5663706 * pow(max(0.001, 1.0 + g * g - 2.0 * g * mu), 1.5));
}

void main() {
    vec3 ro = uCameraLocal;
    vec3 rd = normalize(vOpLocal - ro);
    vec2 interval = opBoxInterval(ro, rd);
    float start = max(0.0, interval.x), end = interval.y;
    if (end <= start) discard;
    float ds = (end - start) / 96.0;
    vec3 T = vec3(1.0), L = vec3(0.0);
    for (int i = 0; i < 96; i++) {
        vec3 p = ro + rd * (start + (float(i) + 0.5) * ds);
        float density = opDensity(p);
        vec3 sigmaS = uScatterColor * density;
        vec3 sigmaT = (uAbsorption + uScatterColor) * density;
        vec3 lightDir = normalize(uLightPosition - p);
        vec2 lightRange = opBoxInterval(p, lightDir);
        float shadowStep = max(lightRange.y, 0.0) / 8.0;
        vec3 opticalDepth = vec3(0.0);
        for (int j = 0; j < 8; j++) {
            opticalDepth += (uAbsorption + uScatterColor) *
                opDensity(p + lightDir * (float(j) + 0.5) * shadowStep) * shadowStep;
        }
        vec3 incident = uAmbient + uLightColor * exp(-opticalDepth) * opHG(dot(lightDir, -rd));
        vec3 emission = uScatterColor * uExcitation * exp(-dot(p - uTouch, p - uTouch) * 14.0);
        vec3 segmentT = exp(-sigmaT * ds);
        vec3 integral = (vec3(1.0) - segmentT) / max(sigmaT, vec3(0.0001));
        L += T * (sigmaS * incident + emission * density) * integral;
        T *= segmentT;
        if (max(max(T.r, T.g), T.b) < 0.002) break;
    }
    float alpha = 1.0 - dot(T, vec3(0.2126, 0.7152, 0.0722));
    // Straight alpha for standard transparent compositing. Medium transport itself
    // is RGB; alpha composition cannot recursively refract other transparent volumes.
    fragColor = vec4(L / max(alpha, 0.0001), alpha);
}

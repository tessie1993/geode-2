#version 300 es
precision highp float;
precision highp sampler2D;
precision highp sampler3D;

// createVolumeCausticsMaterial (src/optics.js): photon-driven single scattering, ray-marching
// the six directional grids of a PhotonVolume (+X, -X, +Y, -Y, +Z, -Z) with a Henyey-Greenstein
// weight each, uSteps view samples (32-512), optionally ending rays at the scene depth. Written
// into the linear HDR target, where three.js applies no tone mapping or colour conversion.
in vec3 vPhotonWorld;
out vec4 fragColor;

uniform sampler3D uVolume0, uVolume1, uVolume2, uVolume3, uVolume4, uVolume5;
uniform vec3 uBoundsMin, uBoundsMax;
uniform float uExtinction, uAnisotropy, uIntensity;
uniform sampler2D uSceneDepth;
uniform float uUseDepth;
uniform vec2 uResolution;
uniform mat4 uProjectionInverse, uCameraWorld;
uniform vec3 uCameraPosition;
uniform int uSteps;

float phase(float mu) {
    float g = uAnisotropy;
    return (1.0 - g * g) / (12.5663706 * pow(max(.001, 1.0 + g * g - 2.0 * g * mu), 1.5));
}

void main() {
    vec3 ro = uCameraPosition, rd = normalize(vPhotonWorld - ro), inv = 1.0 / (rd + vec3(1e-8));
    vec3 a = (uBoundsMin - ro) * inv, b = (uBoundsMax - ro) * inv, lo = min(a, b), hi = max(a, b);
    float start = max(0.0, max(max(lo.x, lo.y), lo.z)), end = min(min(hi.x, hi.y), hi.z);
    if (uUseDepth > .5) {
        float depth = texture(uSceneDepth, gl_FragCoord.xy / uResolution).r;
        vec4 view = uProjectionInverse *
            vec4(gl_FragCoord.xy / uResolution * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
        view /= view.w;
        vec3 surface = (uCameraWorld * view).xyz;
        end = min(end, dot(surface - ro, rd));
    }
    if (end <= start) discard;
    float ds = (end - start) / float(uSteps), Tr = 1.0;
    vec3 L = vec3(0.0);
    float segmentT = exp(-uExtinction * ds);
    float integral = uExtinction > .00001 ? (1.0 - segmentT) / uExtinction : ds;
    for (int i = 0; i < uSteps; i++) {
        vec3 p = (ro + rd * (start + (float(i) + .5) * ds) - uBoundsMin) /
            (uBoundsMax - uBoundsMin);
        vec3 source = texture(uVolume0, p).rgb * phase(-rd.x) +
            texture(uVolume1, p).rgb * phase(rd.x) +
            texture(uVolume2, p).rgb * phase(-rd.y) +
            texture(uVolume3, p).rgb * phase(rd.y) +
            texture(uVolume4, p).rgb * phase(-rd.z) +
            texture(uVolume5, p).rgb * phase(rd.z);
        L += Tr * source * integral * uIntensity;
        Tr *= segmentT;
        if (Tr < .001) break;
    }
    float alpha = 1.0 - Tr;
    fragColor = vec4(L / max(alpha, .00001), alpha);
}

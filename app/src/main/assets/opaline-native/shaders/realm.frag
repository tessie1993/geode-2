#version 300 es
precision highp float;
precision highp sampler2D;
precision highp samplerCube;

#include "lib/three-physical.glsl"

in vec3 vLocal;
in vec4 vUv;
in float vFogDepth;

layout(location = 0) out vec4 fragColor;

uniform int uMode;
uniform vec3 uColor; // linear
uniform sampler2D uMap;
uniform vec2 uPlateSize;
uniform float uFogDensity;
uniform vec3 uFogColor;
uniform float uLight;
uniform float uOpacity;
uniform bool uLinearOutput;

// Reflector.js
float blendOverlay(float base, float blend) {
    return base < 0.5 ? (2.0 * base * blend) : (1.0 - 2.0 * (1.0 - base) * (1.0 - blend));
}

vec3 blendOverlay(vec3 base, vec3 blend) {
    return vec3(blendOverlay(base.r, blend.r), blendOverlay(base.g, blend.g), blendOverlay(base.b, blend.b));
}

void main() {
    vec3 color = uColor;
    float alpha = 1.0;
    if (uMode == 1) {
        // MeshBasicMaterial: the sRGB backdrop map times its colour, then FogExp2. Bitmaps upload
        // top row first, so v runs downward.
        vec2 uv = vec2(vLocal.x / uPlateSize.x + 0.5, 0.5 - vLocal.y / uPlateSize.y);
        color *= sRGBTransferEOTF(texture(uMap, uv).rgb);
        color = mix(color, uFogColor, 1.0 - exp(-uFogDensity * uFogDensity * vFogDepth * vFogDepth));
    } else if (uMode == 2) {
        color = blendOverlay(textureLod(uMap, vUv.xy / vUv.w, 0.0).rgb, uColor);
    } else if (uMode == 3) {
        alpha = pow(max(0.0, 1.0 - length(vLocal.xy)), 3.0) * 0.08;
    } else if (uMode == 4) {
        alpha = uOpacity;
    }
    color *= uLight;
    fragColor = vec4(uLinearOutput ? color : sRGBTransferOETF(ACESFilmicToneMapping(color)), alpha);
}

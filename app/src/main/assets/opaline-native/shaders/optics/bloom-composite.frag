#version 300 es
precision highp float;
precision highp sampler2D;

// UnrealBloomPass._getCompositeMaterial(5) (three.js r186).
in vec2 vUv;
out vec4 fragColor;

uniform sampler2D blurTexture1;
uniform sampler2D blurTexture2;
uniform sampler2D blurTexture3;
uniform sampler2D blurTexture4;
uniform sampler2D blurTexture5;
uniform float bloomStrength;
uniform float bloomRadius;
uniform float bloomFactors[5];
uniform vec3 bloomTintColors[5];

float lerpBloomFactor(const in float factor) {
    float mirrorFactor = 1.2 - factor;
    return mix(factor, mirrorFactor, bloomRadius);
}

void main() {
    // 3.0 for backwards compatibility with previous alpha-based intensity
    vec3 bloom = 3.0 * bloomStrength * (
        lerpBloomFactor(bloomFactors[0]) * bloomTintColors[0] * texture(blurTexture1, vUv).rgb +
        lerpBloomFactor(bloomFactors[1]) * bloomTintColors[1] * texture(blurTexture2, vUv).rgb +
        lerpBloomFactor(bloomFactors[2]) * bloomTintColors[2] * texture(blurTexture3, vUv).rgb +
        lerpBloomFactor(bloomFactors[3]) * bloomTintColors[3] * texture(blurTexture4, vUv).rgb +
        lerpBloomFactor(bloomFactors[4]) * bloomTintColors[4] * texture(blurTexture5, vUv).rgb
    );
    float bloomAlpha = max(bloom.r, max(bloom.g, bloom.b));
    fragColor = vec4(bloom, bloomAlpha);
}

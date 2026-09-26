#version 300 es
precision highp float;
precision highp sampler2D;

// UnrealBloomPass._getSeparableBlurMaterial (three.js r186). KERNEL_PAIRS differs per mip
// (kernel sizes 6, 10, 14, 18, 22 give 3, 5, 7, 9, 11 bilinear pairs), so it is a uniform here.
in vec2 vUv;
out vec4 fragColor;

uniform sampler2D colorTexture;
uniform vec2 invSize;
uniform vec2 direction;
uniform float centerWeight;
uniform int kernelPairs;
uniform float gaussianOffsets[11];
uniform float gaussianWeights[11];

void main() {
    vec3 diffuseSum = texture(colorTexture, vUv).rgb * centerWeight;
    for (int i = 0; i < kernelPairs; i++) {
        vec2 uvOffset = direction * invSize * gaussianOffsets[i];
        vec3 sample1 = texture(colorTexture, vUv + uvOffset).rgb;
        vec3 sample2 = texture(colorTexture, vUv - uvOffset).rgb;
        diffuseSum += (sample1 + sample2) * gaussianWeights[i];
    }
    fragColor = vec4(diffuseSum, 1.0);
}

#version 300 es
precision highp float;
precision highp sampler2D;

// three.js r186 LuminosityHighPassShader as UnrealBloomPass uses it: defaultColor 0x000000,
// defaultOpacity 0, smoothWidth .01, luminosityThreshold = the pass threshold.
in vec2 vUv;
out vec4 fragColor;

uniform sampler2D tDiffuse;
uniform float luminosityThreshold;
uniform float smoothWidth;

// WebGLProgram getLuminanceFunction(): the Rec. 709 coefficients of linear sRGB.
float luminance(const in vec3 rgb) {
    const vec3 weights = vec3(0.2126, 0.7152, 0.0722);
    return dot(weights, rgb);
}

void main() {
    vec4 texel = texture(tDiffuse, vUv);
    float v = luminance(texel.xyz);
    vec4 outputColor = vec4(0.0);
    float alpha = smoothstep(luminosityThreshold, luminosityThreshold + smoothWidth, v);
    fragColor = mix(outputColor, texel, alpha);
}

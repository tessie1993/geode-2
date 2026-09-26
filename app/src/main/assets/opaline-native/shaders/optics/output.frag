#version 300 es
precision highp float;
precision highp sampler2D;

// three.js r186 OutputShader with ACES_FILMIC_TONE_MAPPING and SRGB_TRANSFER defined (the
// workbench renderer's toneMapping and outputColorSpace), with the ShaderChunk
// tonemapping_pars_fragment and colorspace_pars_fragment functions it uses.
in vec2 vUv;
out vec4 fragColor;

uniform sampler2D tDiffuse;
uniform sampler2D bloomTexture;
uniform float toneMappingExposure;

#define saturate(a) clamp(a, 0.0, 1.0)

// source: https://github.com/selfshadow/ltc_code/blob/master/webgl/shaders/ltc/ltc_blit.fs
vec3 RRTAndODTFit(vec3 v) {
    vec3 a = v * (v + 0.0245786) - 0.000090537;
    vec3 b = v * (0.983729 * v + 0.4329510) + 0.238081;
    return a / b;
}

// this implementation of ACES is modified to accommodate a brighter viewing environment.
// the scale factor of 1/0.6 is subjective. see discussion in #19621.
vec3 ACESFilmicToneMapping(vec3 color) {
    // sRGB => XYZ => D65_2_D60 => AP1 => RRT_SAT
    const mat3 ACESInputMat = mat3(
        vec3(0.59719, 0.07600, 0.02840), // transposed from source
        vec3(0.35458, 0.90834, 0.13383),
        vec3(0.04823, 0.01566, 0.83777)
    );
    // ODT_SAT => XYZ => D60_2_D65 => sRGB
    const mat3 ACESOutputMat = mat3(
        vec3(1.60475, -0.10208, -0.00327), // transposed from source
        vec3(-0.53108, 1.10813, -0.07276),
        vec3(-0.07367, -0.00605, 1.07602)
    );
    color *= toneMappingExposure / 0.6;
    color = ACESInputMat * color;
    // Apply RRT and ODT
    color = RRTAndODTFit(color);
    color = ACESOutputMat * color;
    // Clamp to [0, 1]
    return saturate(color);
}

vec4 sRGBTransferOETF(in vec4 value) {
    return vec4(mix(pow(value.rgb, vec3(0.41666)) * 1.055 - vec3(0.055), value.rgb * 12.92,
        vec3(lessThanEqual(value.rgb, vec3(0.0031308)))), value.a);
}

void main() {
    // UnrealBloomPass blends its composite over the read buffer (AdditiveBlending with
    // premultipliedAlpha: ONE, ONE); OutputPass then reads that sum at the same UV.
    fragColor = texture(tDiffuse, vUv) + texture(bloomTexture, vUv);
    // tone mapping
    fragColor.rgb = ACESFilmicToneMapping(fragColor.rgb);
    // color space
    fragColor = sRGBTransferOETF(fragColor);
}

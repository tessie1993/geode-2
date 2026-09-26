// Physical lighting for the Opaline surface pass, ported to GLSL ES 3.00 from the three.js r186
// shader chunks vendored in ui-system/Opaline-3D-Library/vendor/three (MIT, see
// app/src/main/assets/opaline-native/THIRD-PARTY-NOTICES.md). The library renders every element
// through MeshPhysicalMaterial; these are the same equations with the same constants, grouped by
// the chunk they come from. Only the environment lookup differs: three.js samples a PMREM
// cube-UV atlas, this pass samples a prefiltered cube map whose mip level is the roughness.

// --- common.glsl.js ---
#define PI 3.141592653589793
#define PI2 6.283185307179586
#define RECIPROCAL_PI 0.3183098861837907
#define EPSILON 1e-6
#define saturate(a) clamp(a, 0.0, 1.0)

float pow2(const in float x) { return x * x; }
vec3 pow2(const in vec3 x) { return x * x; }
float pow4(const in float x) { float x2 = x * x; return x2 * x2; }
float max3(const in vec3 v) { return max(max(v.x, v.y), v.z); }

vec3 BRDF_Lambert(const in vec3 diffuseColor) { return RECIPROCAL_PI * diffuseColor; }

vec3 F_Schlick(const in vec3 f0, const in float f90, const in float dotVH) {
    float fresnel = exp2((-5.55473 * dotVH - 6.98316) * dotVH);
    return f0 * (1.0 - fresnel) + (f90 * fresnel);
}

float F_Schlick(const in float f0, const in float f90, const in float dotVH) {
    float fresnel = exp2((-5.55473 * dotVH - 6.98316) * dotVH);
    return f0 * (1.0 - fresnel) + (f90 * fresnel);
}

// --- colorspace_pars_fragment.glsl.js ---
vec3 sRGBTransferEOTF(in vec3 value) {
    return mix(pow(value * 0.9478672986 + vec3(0.0521327014), vec3(2.4)), value * 0.0773993808,
        vec3(lessThanEqual(value, vec3(0.04045))));
}

vec3 sRGBTransferOETF(in vec3 value) {
    return mix(pow(value, vec3(0.41666)) * 1.055 - vec3(0.055), value * 12.92,
        vec3(lessThanEqual(value, vec3(0.0031308))));
}

// --- tonemapping_pars_fragment.glsl.js ---
uniform float toneMappingExposure;

vec3 RRTAndODTFit(vec3 v) {
    vec3 a = v * (v + 0.0245786) - 0.000090537;
    vec3 b = v * (0.983729 * v + 0.4329510) + 0.238081;
    return a / b;
}

vec3 ACESFilmicToneMapping(vec3 color) {
    const mat3 ACESInputMat = mat3(
        vec3(0.59719, 0.07600, 0.02840),
        vec3(0.35458, 0.90834, 0.13383),
        vec3(0.04823, 0.01566, 0.83777)
    );
    const mat3 ACESOutputMat = mat3(
        vec3(1.60475, -0.10208, -0.00327),
        vec3(-0.53108, 1.10813, -0.07276),
        vec3(-0.07367, -0.00605, 1.07602)
    );
    color *= toneMappingExposure / 0.6;
    color = ACESInputMat * color;
    color = RRTAndODTFit(color);
    color = ACESOutputMat * color;
    return saturate(color);
}

const mat3 LINEAR_REC2020_TO_LINEAR_SRGB = mat3(
    vec3(1.6605, -0.1246, -0.0182),
    vec3(-0.5876, 1.1329, -0.1006),
    vec3(-0.0728, -0.0083, 1.1187)
);
const mat3 LINEAR_SRGB_TO_LINEAR_REC2020 = mat3(
    vec3(0.6274, 0.0691, 0.0164),
    vec3(0.3293, 0.9195, 0.0880),
    vec3(0.0433, 0.0113, 0.8956)
);

vec3 agxDefaultContrastApprox(vec3 x) {
    vec3 x2 = x * x;
    vec3 x4 = x2 * x2;
    return 15.5 * x4 * x2 - 40.14 * x4 * x + 31.96 * x4 - 6.868 * x2 * x + 0.4298 * x2 + 0.1191 * x - 0.00232;
}

// Blender's AgX view transform as implemented by three.js; used for the Cycles reference scenes.
vec3 AgXToneMapping(vec3 color) {
    const mat3 AgXInsetMatrix = mat3(
        vec3(0.856627153315983, 0.137318972929847, 0.11189821299995),
        vec3(0.0951212405381588, 0.761241990602591, 0.0767994186031903),
        vec3(0.0482516061458583, 0.101439036467562, 0.811302368396859)
    );
    const mat3 AgXOutsetMatrix = mat3(
        vec3(1.1271005818144368, -0.1413297634984383, -0.14132976349843826),
        vec3(-0.11060664309660323, 1.157823702216272, -0.11060664309660294),
        vec3(-0.016493938717834573, -0.016493938717834257, 1.2519364065950405)
    );
    const float AgxMinEv = -12.47393;
    const float AgxMaxEv = 4.026069;
    color *= toneMappingExposure;
    color = LINEAR_SRGB_TO_LINEAR_REC2020 * color;
    color = AgXInsetMatrix * color;
    color = max(color, 1e-10);
    color = log2(color);
    color = (color - AgxMinEv) / (AgxMaxEv - AgxMinEv);
    color = clamp(color, 0.0, 1.0);
    color = agxDefaultContrastApprox(color);
    color = AgXOutsetMatrix * color;
    color = pow(max(vec3(0.0), color), vec3(2.2));
    color = LINEAR_REC2020_TO_LINEAR_SRGB * color;
    return clamp(color, 0.0, 1.0);
}

// --- iridescence_fragment.glsl.js ---
const mat3 XYZ_TO_REC709 = mat3(
    3.2404542, -0.9692660, 0.0556434,
    -1.5371385, 1.8760108, -0.2040259,
    -0.4985314, 0.0415560, 1.0572252
);

vec3 Fresnel0ToIor(vec3 fresnel0) {
    vec3 sqrtF0 = sqrt(fresnel0);
    return (vec3(1.0) + sqrtF0) / (vec3(1.0) - sqrtF0);
}

vec3 IorToFresnel0(vec3 transmittedIor, float incidentIor) {
    return pow2((transmittedIor - vec3(incidentIor)) / (transmittedIor + vec3(incidentIor)));
}

float IorToFresnel0(float transmittedIor, float incidentIor) {
    return pow2((transmittedIor - incidentIor) / (transmittedIor + incidentIor));
}

vec3 evalSensitivity(float OPD, vec3 shift) {
    float phase = 2.0 * PI * OPD * 1.0e-9;
    vec3 val = vec3(5.4856e-13, 4.4201e-13, 5.2481e-13);
    vec3 pos = vec3(1.6810e+06, 1.7953e+06, 2.2084e+06);
    vec3 var = vec3(4.3278e+09, 9.3046e+09, 6.6121e+09);
    vec3 xyz = val * sqrt(2.0 * PI * var) * cos(pos * phase + shift) * exp(-pow2(phase) * var);
    xyz.x += 9.7470e-14 * sqrt(2.0 * PI * 4.5282e+09) * cos(2.2399e+06 * phase + shift[0]) * exp(-4.5282e+09 * pow2(phase));
    xyz /= 1.0685e-7;
    return XYZ_TO_REC709 * xyz;
}

vec3 evalIridescence(float outsideIOR, float eta2, float cosTheta1, float thinFilmThickness, vec3 baseF0) {
    float iridescenceIOR = mix(outsideIOR, eta2, smoothstep(0.0, 0.03, thinFilmThickness));
    float sinTheta2Sq = pow2(outsideIOR / iridescenceIOR) * (1.0 - pow2(cosTheta1));
    float cosTheta2Sq = 1.0 - sinTheta2Sq;
    if (cosTheta2Sq < 0.0) return vec3(1.0);
    float cosTheta2 = sqrt(cosTheta2Sq);
    float R0 = IorToFresnel0(iridescenceIOR, outsideIOR);
    float R12 = F_Schlick(R0, 1.0, cosTheta1);
    float T121 = 1.0 - R12;
    float phi12 = 0.0;
    if (iridescenceIOR < outsideIOR) phi12 = PI;
    float phi21 = PI - phi12;
    vec3 baseIOR = Fresnel0ToIor(clamp(baseF0, 0.0, 0.9999));
    vec3 R1 = IorToFresnel0(baseIOR, iridescenceIOR);
    vec3 R23 = F_Schlick(R1, 1.0, cosTheta2);
    vec3 phi23 = vec3(0.0);
    if (baseIOR[0] < iridescenceIOR) phi23[0] = PI;
    if (baseIOR[1] < iridescenceIOR) phi23[1] = PI;
    if (baseIOR[2] < iridescenceIOR) phi23[2] = PI;
    float OPD = 2.0 * iridescenceIOR * thinFilmThickness * cosTheta2;
    vec3 phi = vec3(phi21) + phi23;
    vec3 R123 = clamp(R12 * R23, 1e-5, 0.9999);
    vec3 r123 = sqrt(R123);
    vec3 Rs = pow2(T121) * R23 / (vec3(1.0) - R123);
    vec3 C0 = R12 + Rs;
    vec3 I = C0;
    vec3 Cm = Rs - T121;
    for (int m = 1; m <= 2; ++m) {
        Cm *= r123;
        vec3 Sm = 2.0 * evalSensitivity(float(m) * OPD, float(m) * phi);
        I += Cm * Sm;
    }
    return max(I, vec3(0.0));
}

// --- lights_physical_pars_fragment.glsl.js ---
uniform sampler2D dfgLUT;

struct PhysicalMaterial {
    vec3 diffuseColor;
    vec3 diffuseContribution;
    vec3 specularColor;
    vec3 specularColorBlended;
    float roughness;
    float metalness;
    float specularF90;
    float dispersion;
    vec2 dfg;
    vec3 multiScatteringCompensation;
    float clearcoat;
    float clearcoatRoughness;
    vec3 clearcoatF0;
    float clearcoatF90;
    float iridescence;
    float iridescenceIOR;
    float iridescenceThickness;
    vec3 iridescenceFresnel;
    vec3 iridescenceF0Dielectric;
    vec3 sheenColor;
    float sheenRoughness;
    float ior;
    float transmission;
    float transmissionAlpha;
    float thickness;
    float attenuationDistance;
    vec3 attenuationColor;
};

struct IncidentLight {
    vec3 color;
    vec3 direction;
};

struct ReflectedLight {
    vec3 directDiffuse;
    vec3 directSpecular;
    vec3 indirectDiffuse;
    vec3 indirectSpecular;
};

vec3 clearcoatSpecularDirect = vec3(0.0);
vec3 clearcoatSpecularIndirect = vec3(0.0);
vec3 sheenSpecularDirect = vec3(0.0);
vec3 sheenSpecularIndirect = vec3(0.0);

vec3 Schlick_to_F0(const in vec3 f, const in float f90, const in float dotVH) {
    float x = clamp(1.0 - dotVH, 0.0, 1.0);
    float x2 = x * x;
    float x5 = clamp(x * x2 * x2, 0.0, 0.9999);
    return (f - vec3(f90) * x5) / (1.0 - x5);
}

float V_GGX_SmithCorrelated(const in float alpha, const in float dotNL, const in float dotNV) {
    float a2 = pow2(alpha);
    float gv = dotNL * sqrt(a2 + (1.0 - a2) * pow2(dotNV));
    float gl = dotNV * sqrt(a2 + (1.0 - a2) * pow2(dotNL));
    return 0.5 / max(gv + gl, EPSILON);
}

float D_GGX(const in float alpha, const in float dotNH) {
    float a2 = pow2(alpha);
    float denom = pow2(dotNH) * (a2 - 1.0) + 1.0;
    return RECIPROCAL_PI * a2 / pow2(denom);
}

vec3 BRDF_GGX_Clearcoat(const in vec3 lightDir, const in vec3 viewDir, const in vec3 normal, const in PhysicalMaterial material) {
    vec3 f0 = material.clearcoatF0;
    float f90 = material.clearcoatF90;
    float alpha = pow2(material.clearcoatRoughness);
    vec3 halfDir = normalize(lightDir + viewDir);
    float dotNL = saturate(dot(normal, lightDir));
    float dotNV = saturate(dot(normal, viewDir));
    float dotNH = saturate(dot(normal, halfDir));
    float dotVH = saturate(dot(viewDir, halfDir));
    vec3 F = F_Schlick(f0, f90, dotVH);
    float V = V_GGX_SmithCorrelated(alpha, dotNL, dotNV);
    float D = D_GGX(alpha, dotNH);
    return F * (V * D);
}

vec3 BRDF_GGX(const in vec3 lightDir, const in vec3 viewDir, const in vec3 normal, const in PhysicalMaterial material) {
    vec3 f0 = material.specularColorBlended;
    float f90 = material.specularF90;
    float alpha = pow2(material.roughness);
    vec3 halfDir = normalize(lightDir + viewDir);
    float dotNL = saturate(dot(normal, lightDir));
    float dotNV = saturate(dot(normal, viewDir));
    float dotNH = saturate(dot(normal, halfDir));
    float dotVH = saturate(dot(viewDir, halfDir));
    vec3 F = F_Schlick(f0, f90, dotVH);
    F = mix(F, material.iridescenceFresnel, material.iridescence);
    float V = V_GGX_SmithCorrelated(alpha, dotNL, dotNV);
    float D = D_GGX(alpha, dotNH);
    return F * (V * D);
}

float D_Charlie(float roughness, float dotNH) {
    float alpha = pow2(roughness);
    float invAlpha = 1.0 / alpha;
    float cos2h = dotNH * dotNH;
    float sin2h = max(1.0 - cos2h, 0.0078125);
    return (2.0 + invAlpha) * pow(sin2h, invAlpha * 0.5) / (2.0 * PI);
}

float V_Neubelt(float dotNV, float dotNL) {
    return saturate(1.0 / (4.0 * (dotNL + dotNV - dotNL * dotNV)));
}

vec3 BRDF_Sheen(const in vec3 lightDir, const in vec3 viewDir, const in vec3 normal, vec3 sheenColor, const in float sheenRoughness) {
    vec3 halfDir = normalize(lightDir + viewDir);
    float dotNL = saturate(dot(normal, lightDir));
    float dotNV = saturate(dot(normal, viewDir));
    float dotNH = saturate(dot(normal, halfDir));
    float D = D_Charlie(sheenRoughness, dotNH);
    float V = V_Neubelt(dotNV, dotNL);
    return sheenColor * (D * V);
}

float IBLSheenBRDF(const in vec3 normal, const in vec3 viewDir, const in float roughness) {
    float dotNV = saturate(dot(normal, viewDir));
    float r2 = roughness * roughness;
    float rInv = 1.0 / (roughness + 0.1);
    float a = -1.9362 + 1.0678 * roughness + 0.4573 * r2 - 0.8469 * rInv;
    float b = -0.6014 + 0.5538 * roughness - 0.4670 * r2 - 0.1255 * rInv;
    float DG = exp(a * dotNV + b);
    return saturate(DG);
}

vec3 EnvironmentBRDF(const in vec3 normal, const in vec3 viewDir, const in vec3 specularColor, const in float specularF90, const in float roughness) {
    float dotNV = saturate(dot(normal, viewDir));
    vec2 fab = texture(dfgLUT, vec2(roughness, dotNV)).rg;
    return specularColor * fab.x + specularF90 * fab.y;
}

void computeMultiscatteringIridescence(const in vec2 fab, const in vec3 specularColor, const in float specularF90,
    const in float iridescence, const in vec3 iridescenceF0, inout vec3 singleScatter, inout vec3 multiScatter) {
    vec3 Fr = mix(specularColor, iridescenceF0, iridescence);
    vec3 FssEss = Fr * fab.x + specularF90 * fab.y;
    float Ess = fab.x + fab.y;
    float Ems = 1.0 - Ess;
    vec3 Favg = Fr + (1.0 - Fr) * 0.047619;
    vec3 Fms = FssEss * Favg / (1.0 - Ems * Favg);
    singleScatter += FssEss;
    multiScatter += Fms * Ems;
}

void RE_Direct_Physical(const in IncidentLight directLight, const in vec3 geometryNormal, const in vec3 geometryViewDir,
    const in vec3 geometryClearcoatNormal, const in PhysicalMaterial material, inout ReflectedLight reflectedLight) {
    float dotNL = saturate(dot(geometryNormal, directLight.direction));
    vec3 irradiance = dotNL * directLight.color;
    float dotNLcc = saturate(dot(geometryClearcoatNormal, directLight.direction));
    vec3 ccIrradiance = dotNLcc * directLight.color;
    clearcoatSpecularDirect += ccIrradiance * BRDF_GGX_Clearcoat(directLight.direction, geometryViewDir, geometryClearcoatNormal, material);
    sheenSpecularDirect += irradiance * BRDF_Sheen(directLight.direction, geometryViewDir, geometryNormal, material.sheenColor, material.sheenRoughness);
    float sheenAlbedoV = IBLSheenBRDF(geometryNormal, geometryViewDir, material.sheenRoughness);
    float sheenAlbedoL = IBLSheenBRDF(geometryNormal, directLight.direction, material.sheenRoughness);
    float sheenEnergyComp = 1.0 - max3(material.sheenColor) * max(sheenAlbedoV, sheenAlbedoL);
    irradiance *= sheenEnergyComp;
    vec3 specularBRDF = BRDF_GGX(directLight.direction, geometryViewDir, geometryNormal, material);
    reflectedLight.directSpecular += irradiance * specularBRDF * material.multiScatteringCompensation;
    vec3 halfDir = normalize(directLight.direction + geometryViewDir);
    float dotVH = saturate(dot(geometryViewDir, halfDir));
    vec3 F = F_Schlick(material.specularColor, material.specularF90, dotVH);
    reflectedLight.directDiffuse += irradiance * BRDF_Lambert(material.diffuseContribution) * (1.0 - F);
}

void RE_IndirectDiffuse_Physical(const in vec3 irradiance, const in vec3 geometryNormal, const in vec3 geometryViewDir,
    const in PhysicalMaterial material, inout ReflectedLight reflectedLight) {
    vec3 singleScattering = vec3(0.0);
    vec3 multiScattering = vec3(0.0);
    computeMultiscatteringIridescence(material.dfg, material.specularColor, material.specularF90, material.iridescence,
        material.iridescenceF0Dielectric, singleScattering, multiScattering);
    vec3 diffuse = irradiance * BRDF_Lambert(material.diffuseContribution) * (1.0 - singleScattering - multiScattering);
    float sheenAlbedo = IBLSheenBRDF(geometryNormal, geometryViewDir, material.sheenRoughness);
    sheenSpecularIndirect += irradiance * material.sheenColor * sheenAlbedo * RECIPROCAL_PI;
    float sheenEnergyComp = 1.0 - max3(material.sheenColor) * sheenAlbedo;
    diffuse *= sheenEnergyComp;
    reflectedLight.indirectDiffuse += diffuse;
}

void RE_IndirectSpecular_Physical(const in vec3 radiance, const in vec3 irradiance, const in vec3 clearcoatRadiance,
    const in vec3 geometryNormal, const in vec3 geometryViewDir, const in vec3 geometryClearcoatNormal,
    const in PhysicalMaterial material, inout ReflectedLight reflectedLight) {
    clearcoatSpecularIndirect += clearcoatRadiance * EnvironmentBRDF(geometryClearcoatNormal, geometryViewDir,
        material.clearcoatF0, material.clearcoatF90, material.clearcoatRoughness);
    sheenSpecularIndirect += irradiance * material.sheenColor * IBLSheenBRDF(geometryNormal, geometryViewDir, material.sheenRoughness) * RECIPROCAL_PI;
    // Multiscattering for the dielectric and the metallic layer, mixed by metalness. No family is
    // both iridescent and metallic, so the metallic layer reuses the dielectric film Fresnel.
    vec3 singleScatteringDielectric = vec3(0.0);
    vec3 multiScatteringDielectric = vec3(0.0);
    vec3 singleScatteringMetallic = vec3(0.0);
    vec3 multiScatteringMetallic = vec3(0.0);
    computeMultiscatteringIridescence(material.dfg, material.specularColor, material.specularF90, material.iridescence,
        material.iridescenceF0Dielectric, singleScatteringDielectric, multiScatteringDielectric);
    computeMultiscatteringIridescence(material.dfg, material.diffuseColor, material.specularF90, material.iridescence,
        material.iridescenceF0Dielectric, singleScatteringMetallic, multiScatteringMetallic);
    vec3 singleScattering = mix(singleScatteringDielectric, singleScatteringMetallic, material.metalness);
    vec3 multiScattering = mix(multiScatteringDielectric, multiScatteringMetallic, material.metalness);
    vec3 diffuse = material.diffuseContribution * (1.0 - (singleScatteringDielectric + multiScatteringDielectric));
    vec3 cosineWeightedIrradiance = irradiance * RECIPROCAL_PI;
    vec3 indirectSpecular = radiance * singleScattering;
    indirectSpecular += multiScattering * cosineWeightedIrradiance;
    vec3 indirectDiffuse = diffuse * cosineWeightedIrradiance;
    float sheenAlbedo = IBLSheenBRDF(geometryNormal, geometryViewDir, material.sheenRoughness);
    float sheenEnergyComp = 1.0 - max3(material.sheenColor) * sheenAlbedo;
    indirectSpecular *= sheenEnergyComp;
    indirectDiffuse *= sheenEnergyComp;
    reflectedLight.indirectSpecular += indirectSpecular;
    reflectedLight.indirectDiffuse += indirectDiffuse;
}

// --- lights_pars_begin.glsl.js ---
float getDistanceAttenuation(const in float lightDistance, const in float cutoffDistance, const in float decayExponent) {
    float distanceFalloff = 1.0 / max(pow(lightDistance, decayExponent), 0.01);
    if (cutoffDistance > 0.0) {
        distanceFalloff *= pow2(saturate(1.0 - pow4(lightDistance / cutoffDistance)));
    }
    return distanceFalloff;
}

vec3 getHemisphereLightIrradiance(const in vec3 skyColor, const in vec3 groundColor, const in vec3 direction, const in vec3 normal) {
    float dotNL = dot(normal, direction);
    float hemiDiffuseWeight = 0.5 * dotNL + 0.5;
    return mix(groundColor, skyColor, hemiDiffuseWeight);
}

// --- shadowmap_pars_fragment.glsl.js (SHADOWMAP_TYPE_PCF, one directional shadow) ---
precision highp sampler2DShadow;
uniform sampler2DShadow uShadowMap;
uniform vec2 uShadowMapSize;
uniform float uShadowBias;
uniform float uShadowRadius;

float interleavedGradientNoise(vec2 position) {
    return fract(52.9829189 * fract(dot(position, vec2(0.06711056, 0.00583715))));
}

vec2 vogelDiskSample(int sampleIndex, int samplesCount, float phi) {
    const float goldenAngle = 2.399963229728653;
    float r = sqrt((float(sampleIndex) + 0.5) / float(samplesCount));
    float theta = float(sampleIndex) * goldenAngle + phi;
    return vec2(cos(theta), sin(theta)) * r;
}

// shadowIntensity is 1, so the result is the filtered visibility itself.
float getShadow(vec4 shadowCoord) {
    float shadow = 1.0;
    shadowCoord.xyz /= shadowCoord.w;
    shadowCoord.z += uShadowBias;
    bool inFrustum = shadowCoord.x >= 0.0 && shadowCoord.x <= 1.0 && shadowCoord.y >= 0.0 && shadowCoord.y <= 1.0;
    if (inFrustum && shadowCoord.z <= 1.0) {
        vec2 texelSize = vec2(1.0) / uShadowMapSize;
        float radius = uShadowRadius * texelSize.x;
        float phi = interleavedGradientNoise(gl_FragCoord.xy) * PI2;
        shadow = 0.0;
        for (int i = 0; i < 5; i++) {
            shadow += texture(uShadowMap, vec3(shadowCoord.xy + vogelDiskSample(i, 5, phi) * radius, shadowCoord.z));
        }
        shadow *= 0.2;
    }
    return shadow;
}

// --- envmap_physical_pars_fragment.glsl.js (cube map instead of the PMREM cube-UV atlas) ---
uniform samplerCube envMap;
uniform float envMapIntensity;
uniform float envMapMaxLod;

vec3 textureEnvironment(const in vec3 direction, const in float roughness) {
    return textureLod(envMap, direction, roughness * envMapMaxLod).rgb;
}

vec3 getIBLIrradiance(const in vec3 normal) {
    return PI * textureEnvironment(normal, 1.0) * envMapIntensity;
}

vec3 getIBLRadiance(const in vec3 viewDir, const in vec3 normal, const in float roughness) {
    vec3 reflectVec = reflect(-viewDir, normal);
    reflectVec = normalize(mix(reflectVec, normal, pow4(roughness)));
    return textureEnvironment(reflectVec, roughness) * envMapIntensity;
}

// --- transmission_pars_fragment.glsl.js ---
uniform sampler2D transmissionSamplerMap;
uniform vec2 transmissionSamplerSize;

float w0(float a) { return (1.0 / 6.0) * (a * (a * (-a + 3.0) - 3.0) + 1.0); }
float w1(float a) { return (1.0 / 6.0) * (a * a * (3.0 * a - 6.0) + 4.0); }
float w2(float a) { return (1.0 / 6.0) * (a * (a * (-3.0 * a + 3.0) + 3.0) + 1.0); }
float w3(float a) { return (1.0 / 6.0) * (a * a * a); }
float g0(float a) { return w0(a) + w1(a); }
float g1(float a) { return w2(a) + w3(a); }
float h0(float a) { return -1.0 + w1(a) / (w0(a) + w1(a)); }
float h1(float a) { return 1.0 + w3(a) / (w2(a) + w3(a)); }

vec4 bicubic(sampler2D tex, vec2 uv, vec4 texelSize, float lod) {
    uv = uv * texelSize.zw + 0.5;
    vec2 iuv = floor(uv);
    vec2 fuv = fract(uv);
    float g0x = g0(fuv.x);
    float g1x = g1(fuv.x);
    float h0x = h0(fuv.x);
    float h1x = h1(fuv.x);
    float h0y = h0(fuv.y);
    float h1y = h1(fuv.y);
    vec2 p0 = (vec2(iuv.x + h0x, iuv.y + h0y) - 0.5) * texelSize.xy;
    vec2 p1 = (vec2(iuv.x + h1x, iuv.y + h0y) - 0.5) * texelSize.xy;
    vec2 p2 = (vec2(iuv.x + h0x, iuv.y + h1y) - 0.5) * texelSize.xy;
    vec2 p3 = (vec2(iuv.x + h1x, iuv.y + h1y) - 0.5) * texelSize.xy;
    return g0(fuv.y) * (g0x * textureLod(tex, p0, lod) + g1x * textureLod(tex, p1, lod)) +
        g1(fuv.y) * (g0x * textureLod(tex, p2, lod) + g1x * textureLod(tex, p3, lod));
}

vec4 textureBicubic(sampler2D sampler, vec2 uv, float lod) {
    vec2 fLodSize = vec2(textureSize(sampler, int(lod)));
    vec2 cLodSize = vec2(textureSize(sampler, int(lod + 1.0)));
    vec2 fLodSizeInv = 1.0 / fLodSize;
    vec2 cLodSizeInv = 1.0 / cLodSize;
    vec4 fSample = bicubic(sampler, uv, vec4(fLodSizeInv, fLodSize), floor(lod));
    vec4 cSample = bicubic(sampler, uv, vec4(cLodSizeInv, cLodSize), ceil(lod));
    return mix(fSample, cSample, fract(lod));
}

vec3 getVolumeTransmissionRay(const in vec3 n, const in vec3 v, const in float thickness, const in float ior, const in mat4 modelMatrix) {
    vec3 refractionVector = refract(-v, normalize(n), 1.0 / ior);
    vec3 modelScale;
    modelScale.x = length(vec3(modelMatrix[0].xyz));
    modelScale.y = length(vec3(modelMatrix[1].xyz));
    modelScale.z = length(vec3(modelMatrix[2].xyz));
    return normalize(refractionVector) * thickness * modelScale;
}

float applyIorToRoughness(const in float roughness, const in float ior) {
    return roughness * clamp(ior * 2.0 - 2.0, 0.0, 1.0);
}

vec4 getTransmissionSample(const in vec2 fragCoord, const in float roughness, const in float ior) {
    float lod = log2(transmissionSamplerSize.x) * applyIorToRoughness(roughness, ior);
    return textureBicubic(transmissionSamplerMap, fragCoord.xy, lod);
}

// attenuationDistance <= 0 stands for three.js's Infinity: no absorption along the path.
vec3 volumeAttenuation(const in float transmissionDistance, const in vec3 attenuationColor, const in float attenuationDistance) {
    if (attenuationDistance <= 0.0) return vec3(1.0);
    vec3 attenuationCoefficient = -log(attenuationColor) / attenuationDistance;
    return exp(-attenuationCoefficient * transmissionDistance);
}

vec4 getIBLVolumeRefraction(const in vec3 n, const in vec3 v, const in float roughness, const in vec3 diffuseColor,
    const in vec3 specularColor, const in float specularF90, const in vec3 position, const in mat4 modelMatrix,
    const in mat4 projMatrix, const in float dispersion, const in float ior, const in float thickness,
    const in vec3 attenuationColor, const in float attenuationDistance) {
    vec4 transmittedLight = vec4(0.0);
    vec3 transmittance;
    if (dispersion > 0.0) {
        float halfSpread = (ior - 1.0) * 0.025 * dispersion;
        vec3 iors = vec3(ior - halfSpread, ior, ior + halfSpread);
        for (int i = 0; i < 3; i++) {
            vec3 transmissionRay = getVolumeTransmissionRay(n, v, thickness, iors[i], modelMatrix);
            vec3 refractedRayExit = position + transmissionRay;
            vec4 ndcPos = projMatrix * vec4(refractedRayExit, 1.0);
            vec2 refractionCoords = ndcPos.xy / ndcPos.w;
            refractionCoords += 1.0;
            refractionCoords /= 2.0;
            vec4 transmissionSample = getTransmissionSample(refractionCoords, roughness, iors[i]);
            transmittedLight[i] = transmissionSample[i];
            transmittedLight.a += transmissionSample.a;
            transmittance[i] = diffuseColor[i] * volumeAttenuation(length(transmissionRay), attenuationColor, attenuationDistance)[i];
        }
        transmittedLight.a /= 3.0;
    } else {
        vec3 transmissionRay = getVolumeTransmissionRay(n, v, thickness, ior, modelMatrix);
        vec3 refractedRayExit = position + transmissionRay;
        vec4 ndcPos = projMatrix * vec4(refractedRayExit, 1.0);
        vec2 refractionCoords = ndcPos.xy / ndcPos.w;
        refractionCoords += 1.0;
        refractionCoords /= 2.0;
        transmittedLight = getTransmissionSample(refractionCoords, roughness, ior);
        transmittance = diffuseColor * volumeAttenuation(length(transmissionRay), attenuationColor, attenuationDistance);
    }
    vec3 attenuatedColor = transmittance * transmittedLight.rgb;
    vec3 F = EnvironmentBRDF(n, v, specularColor, specularF90, roughness);
    float transmittanceFactor = (transmittance.r + transmittance.g + transmittance.b) / 3.0;
    return vec4((1.0 - F) * attenuatedColor, 1.0 - (1.0 - transmittedLight.a) * transmittanceFactor);
}

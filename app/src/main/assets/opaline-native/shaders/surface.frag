#version 300 es
precision highp float;
precision highp sampler2D;
precision highp samplerCube;

// MeshPhysicalMaterial as the library renders it: the fragment main() of three.js r186
// ShaderLib/meshphysical, with the four opaline.js hooks at the chunks they replace.
#include "lib/three-physical.glsl"
#include "lib/opaline-surface.glsl"

in vec3 vViewPosition;
in vec3 vNormal;
in vec3 vOpPosition;
in vec3 vWorldPosition;
in vec4 vShadowCoord;
in float vOpFilmThickness;

layout(location = 0) out vec4 fragColor;

uniform mat4 uModel, uProjection;

// Material parameters of one src/materials.js family, colours in linear sRGB.
uniform vec3 uDiffuse;
uniform vec3 uEmissive;
uniform float uRoughness;
uniform float uIor;
uniform float uClearcoat;
uniform float uClearcoatRoughness;
uniform float uIridescence;
uniform float uIridescenceIOR;
uniform vec2 uIridescenceThickness;
uniform vec3 uSheenColor;
uniform float uSheenRoughness;
uniform float uTransmission;
uniform float uThickness;
uniform vec3 uAttenuationColor;
uniform float uAttenuationDistance;
uniform float uDispersion;
uniform bool uDoubleSide;
uniform float uMetalness;
uniform float uEnabled;

// The pass: depth only (shadow maps), linear radiance (receiver, reflection, HDR target) or
// ACES + sRGB output; the sun's shadow; realm FogExp2; the realm's background-dim factor (uLight).
uniform bool uDepthOnly;
uniform bool uLinearOutput;
uniform bool uReceiveShadow;
uniform float uFogDensity;
uniform vec3 uFogColor;
uniform float uLight;

// enableFilmThickness: the simulated film's live thickness drives the interference.
uniform bool uFilmThickness;

// attachCaustics on the water: the caustic receiver texture over the patch (x, z, size) it covers.
uniform sampler2D uOpCaustics;
uniform float uOpCausticStrength;
uniform vec3 uCausticPatch;

// The workbench light rig from src/workbench.js, in view space.
uniform vec3 uHemisphereSky, uHemisphereGround, uHemisphereDirection;
uniform vec3 uDirectionalColor[2];
uniform vec3 uDirectionalDirection[2];
uniform vec3 uPointColor[2];
uniform vec3 uPointPosition[2];
uniform float uPointDistance[2];
uniform float uPointDecay[2];

void main() {
    if (uDepthOnly) {
        fragColor = vec4(0.0);
        return;
    }
    vec4 diffuseColor = vec4(uDiffuse, 1.0);
    ReflectedLight reflectedLight = ReflectedLight(vec3(0.0), vec3(0.0), vec3(0.0), vec3(0.0));
    vec3 totalEmissiveRadiance = uEmissive;

    // <color_fragment> + SURFACE_COLOR
    float opCloud = opSurfaceColor(vOpPosition, diffuseColor.rgb);

    // <roughnessmap_fragment> + SURFACE_ROUGHNESS, <metalnessmap_fragment>
    float roughnessFactor = opSurfaceRoughness(vOpPosition, uRoughness);
    float metalnessFactor = uMetalness;

    // <normal_fragment_begin>, <clearcoat_normal_fragment_begin>
    float faceDirection = gl_FrontFacing ? 1.0 : -1.0;
    vec3 normal = normalize(vNormal);
    if (uDoubleSide) normal *= faceDirection;
    vec3 nonPerturbedNormal = normal;
    vec3 clearcoatNormal = nonPerturbedNormal;

    // <emissivemap_fragment> + SURFACE_EMISSION
    totalEmissiveRadiance += opSurfaceEmission(vOpPosition);

    // <lights_physical_fragment>
    PhysicalMaterial material;
    material.diffuseColor = diffuseColor.rgb;
    material.diffuseContribution = diffuseColor.rgb * (1.0 - metalnessFactor);
    material.metalness = metalnessFactor;
    vec3 dxy = max(abs(dFdx(nonPerturbedNormal)), abs(dFdy(nonPerturbedNormal)));
    float geometryRoughness = max(max(dxy.x, dxy.y), dxy.z);
    material.roughness = max(roughnessFactor, 0.0525);
    material.roughness += geometryRoughness;
    material.roughness = min(material.roughness, 1.0);
    material.ior = uIor;
    material.specularF90 = 1.0;
    material.specularColor = min(pow2((material.ior - 1.0) / (material.ior + 1.0)) * vec3(1.0), vec3(1.0));
    material.specularColorBlended = mix(material.specularColor, diffuseColor.rgb, metalnessFactor);
    material.clearcoat = saturate(uClearcoat);
    material.clearcoatRoughness = max(uClearcoatRoughness, 0.0525);
    material.clearcoatRoughness += geometryRoughness;
    material.clearcoatRoughness = min(material.clearcoatRoughness, 1.0);
    material.clearcoatF0 = vec3(0.04);
    material.clearcoatF90 = 1.0;
    material.dispersion = uDispersion;
    material.iridescence = uIridescence;
    material.iridescenceIOR = uIridescenceIOR;
    material.iridescenceThickness = uIridescenceThickness.y;
    material.iridescenceFresnel = vec3(0.0);
    material.iridescenceF0Dielectric = material.specularColor;
    material.sheenColor = uSheenColor;
    material.sheenRoughness = clamp(uSheenRoughness, 0.0001, 1.0);

    // SURFACE_FILM
    if (uIridescence > 0.0) {
        material.iridescenceThickness = opSurfaceFilm(vOpPosition, opCloud, material.iridescenceThickness,
            uIridescenceThickness.x, uIridescenceThickness.y);
        if (uFilmThickness) material.iridescenceThickness = max(0.0, vOpFilmThickness * 1.0e9);
    }

    // <lights_fragment_begin>
    vec3 geometryPosition = -vViewPosition;
    vec3 geometryNormal = normal;
    vec3 geometryViewDir = normalize(vViewPosition);
    vec3 geometryClearcoatNormal = clearcoatNormal;

    material.iridescence = material.iridescenceThickness == 0.0 ? 0.0 : saturate(material.iridescence);
    if (material.iridescence > 0.0) {
        float dotNVi = saturate(dot(normal, geometryViewDir));
        vec3 iridescenceFresnelDielectric = evalIridescence(1.0, material.iridescenceIOR, dotNVi,
            material.iridescenceThickness, material.specularColor);
        material.iridescenceFresnel = iridescenceFresnelDielectric;
        material.iridescenceF0Dielectric = Schlick_to_F0(iridescenceFresnelDielectric, 1.0, dotNVi);
    }

    float dotNVms = saturate(dot(geometryNormal, geometryViewDir));
    material.dfg = texture(dfgLUT, vec2(material.roughness, dotNVms)).rg;
    float EssMs = material.dfg.x + material.dfg.y;
    material.multiScatteringCompensation = 1.0 + material.specularColorBlended * (1.0 / EssMs - 1.0);

    IncidentLight directLight;
    for (int i = 0; i < 2; i++) {
        vec3 lVector = uPointPosition[i] - geometryPosition;
        directLight.direction = normalize(lVector);
        directLight.color = uPointColor[i] * getDistanceAttenuation(length(lVector), uPointDistance[i], uPointDecay[i]);
        RE_Direct_Physical(directLight, geometryNormal, geometryViewDir, geometryClearcoatNormal, material, reflectedLight);
    }
    for (int i = 0; i < 2; i++) {
        // The sun (index 0) casts the rig's only shadow.
        directLight.color = uDirectionalColor[i] * (i == 0 && uReceiveShadow ? getShadow(vShadowCoord) : 1.0);
        directLight.direction = uDirectionalDirection[i];
        RE_Direct_Physical(directLight, geometryNormal, geometryViewDir, geometryClearcoatNormal, material, reflectedLight);
    }

    vec3 iblIrradiance = vec3(0.0);
    vec3 irradiance = getHemisphereLightIrradiance(uHemisphereSky, uHemisphereGround, uHemisphereDirection, geometryNormal);
    vec3 radiance = vec3(0.0);
    vec3 clearcoatRadiance = vec3(0.0);

    // <lights_fragment_maps>
    iblIrradiance += getIBLIrradiance(geometryNormal);
    radiance += getIBLRadiance(geometryViewDir, geometryNormal, material.roughness);
    clearcoatRadiance += getIBLRadiance(geometryViewDir, geometryClearcoatNormal, material.clearcoatRoughness);

    // <lights_fragment_end>
    RE_IndirectDiffuse_Physical(irradiance, geometryNormal, geometryViewDir, material, reflectedLight);
    RE_IndirectSpecular_Physical(radiance, iblIrradiance, clearcoatRadiance, geometryNormal, geometryViewDir,
        geometryClearcoatNormal, material, reflectedLight);

    vec3 totalDiffuse = reflectedLight.directDiffuse + reflectedLight.indirectDiffuse;
    vec3 totalSpecular = reflectedLight.directSpecular + reflectedLight.indirectSpecular;

    // <transmission_fragment>: the receiver holds the scene behind this surface, never itself.
    material.transmission = uTransmission;
    material.transmissionAlpha = 1.0;
    material.thickness = uThickness;
    material.attenuationDistance = uAttenuationDistance;
    material.attenuationColor = uAttenuationColor;
    if (material.transmission > 0.0) {
        vec3 v = normalize(-vWorldPosition);
        vec4 transmitted = getIBLVolumeRefraction(normal, v, material.roughness, material.diffuseContribution,
            material.specularColorBlended, material.specularF90, vWorldPosition, uModel, uProjection,
            material.dispersion, material.ior, material.thickness, material.attenuationColor,
            material.attenuationDistance);
        material.transmissionAlpha = mix(material.transmissionAlpha, transmitted.a, material.transmission);
        totalDiffuse = mix(totalDiffuse, transmitted.rgb, material.transmission);
    }

    vec3 outgoingLight = totalDiffuse + totalSpecular + totalEmissiveRadiance;
    outgoingLight = outgoingLight + sheenSpecularDirect + sheenSpecularIndirect;
    float dotNVcc = saturate(dot(geometryClearcoatNormal, geometryViewDir));
    vec3 Fcc = F_Schlick(material.clearcoatF0, material.clearcoatF90, dotNVcc);
    outgoingLight = outgoingLight * (1.0 - material.clearcoat * Fcc) +
        (clearcoatSpecularDirect + clearcoatSpecularIndirect) * material.clearcoat;

    // A disabled control stays visibly inert; this is host state, not a library material.
    outgoingLight = mix(outgoingLight * .48, outgoingLight, uEnabled);

    if (uOpCausticStrength > 0.0) {
        vec2 causticUv = vec2(vOpPosition.x - uCausticPatch.x, uCausticPatch.y - vOpPosition.z) / uCausticPatch.z + 0.5;
        if (all(greaterThanEqual(causticUv, vec2(0.0))) && all(lessThanEqual(causticUv, vec2(1.0)))) {
            outgoingLight += diffuseColor.rgb * texture(uOpCaustics, causticUv).rgb * uOpCausticStrength / 3.14159265;
        }
    }

    // <fog_fragment> in the composer's linear target, before OutputPass tone maps it.
    float fogFactor = 1.0 - exp(-uFogDensity * uFogDensity * vViewPosition.z * vViewPosition.z);
    outgoingLight = mix(outgoingLight, uFogColor, fogFactor) * uLight;

    // <tonemapping_fragment>, <colorspace_fragment>. Surfaces are opaque draws, as in three.js.
    fragColor = vec4(uLinearOutput ? outgoingLight : sRGBTransferOETF(ACESFilmicToneMapping(outgoingLight)), 1.0);
}

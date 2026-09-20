#ifndef LUMAVALE_FOG_GLSL
#define LUMAVALE_FOG_GLSL

uniform vec3 cameraPosition;
uniform float frameTimeCounter;
uniform float lumavaleBiomeDry;
uniform float lumavaleBiomeRainy;
uniform float lumavaleBiomeSnowy;
uniform float lumavaleSkyExposure;
uniform float lumavaleCaveFactor;

float fogHash12(vec2 value) {
    vec3 p = fract(vec3(value.xyx) * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

float fogValueNoise(vec2 position) {
    vec2 cell = floor(position);
    vec2 local = fract(position);
    local = local * local * (3.0 - 2.0 * local);

    float bottomLeft = fogHash12(cell);
    float bottomRight = fogHash12(cell + vec2(1.0, 0.0));
    float topLeft = fogHash12(cell + vec2(0.0, 1.0));
    float topRight = fogHash12(cell + vec2(1.0, 1.0));
    return mix(
        mix(bottomLeft, bottomRight, local.x),
        mix(topLeft, topRight, local.x),
        local.y
    );
}

vec3 biomeFogColor(vec3 baseFogColor) {
    vec3 dryFog = toLinear(vec3(0.78, 0.69, 0.54));
    vec3 rainyFog = toLinear(vec3(0.47, 0.63, 0.64));
    vec3 snowyFog = toLinear(vec3(0.68, 0.76, 0.84));

    float climateWeight = lumavaleBiomeDry + lumavaleBiomeRainy + lumavaleBiomeSnowy;
    vec3 climateFog =
        dryFog * lumavaleBiomeDry +
        rainyFog * lumavaleBiomeRainy +
        snowyFog * lumavaleBiomeSnowy;
    climateFog /= max(climateWeight, 0.0001);

    return mix(baseFogColor, climateFog, clamp(climateWeight * BIOME_FOG_STRENGTH, 0.0, 1.0));
}

float integrateGroundMist(vec3 cameraRelativeEnd, float skyLight, float rainStrength) {
    float fullRayLength = length(cameraRelativeEnd);
    float rayLength = min(fullRayLength, 160.0);
    if (rayLength < 0.01 || GROUND_FOG_STRENGTH <= 0.0001) {
        return 0.0;
    }

    vec3 rayDirection = cameraRelativeEnd / max(fullRayLength, 0.0001);
    float stepLength = rayLength / float(GROUND_FOG_SAMPLES);
    float opticalDepth = 0.0;
    float localGroundHeight = min(
        cameraPosition.y - 1.62,
        cameraPosition.y + cameraRelativeEnd.y
    );
    float fogCeiling = localGroundHeight + GROUND_FOG_HEIGHT;
    float outdoorFactor = smoothstep(0.08, 0.72, max(skyLight, lumavaleSkyExposure));
    float climateWeight = lumavaleBiomeDry + lumavaleBiomeRainy + lumavaleBiomeSnowy;
    float climateDensity =
        0.82 * lumavaleBiomeDry +
        1.18 * lumavaleBiomeRainy +
        1.08 * lumavaleBiomeSnowy;
    climateDensity /= max(climateWeight, 0.0001);
    climateDensity = mix(1.0, climateDensity, clamp(climateWeight, 0.0, 1.0));

    for (int sampleIndex = 0; sampleIndex < GROUND_FOG_SAMPLES; ++sampleIndex) {
        float sampleDistance = (float(sampleIndex) + 0.5) * stepLength;
        vec3 sampleWorldPosition = cameraPosition + rayDirection * sampleDistance;
        float heightDensity = 1.0 - smoothstep(
            fogCeiling - 0.8,
            fogCeiling + 2.2,
            sampleWorldPosition.y
        );

        vec2 wind = vec2(frameTimeCounter * 0.010, frameTimeCounter * -0.006);
        float broadNoise = fogValueNoise(sampleWorldPosition.xz * 0.021 + wind);
        float detailNoise = fogValueNoise(sampleWorldPosition.xz * 0.057 - wind * 1.7);
        float shapedNoise = smoothstep(0.24, 0.86, broadNoise * 0.72 + detailNoise * 0.28);
        float density = heightDensity * mix(0.24, 1.0, shapedNoise);
        density *= outdoorFactor * climateDensity * mix(1.0, 1.22, rainStrength);
        opticalDepth += density * stepLength * 0.020;
    }

    return clamp(1.0 - exp(-opticalDepth * GROUND_FOG_STRENGTH), 0.0, 0.86);
}

float computeCaveFog(float distanceFromCamera, float pixelSkyLight) {
    float caveFactor = clamp(lumavaleCaveFactor, 0.0, 1.0);
    float openingRelief = mix(1.0, 0.30, pow(clamp(pixelSkyLight, 0.0, 1.0), 2.0));
    float distanceFog = 1.0 - exp(-distanceFromCamera * 0.030);
    return clamp(distanceFog * caveFactor * openingRelief * CAVE_FOG_STRENGTH, 0.0, 0.88);
}

#endif

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

float groundMistPatch(vec3 worldPosition) {
    vec2 wind = vec2(frameTimeCounter * 0.004, frameTimeCounter * -0.0025);
    float broadNoise = fogValueNoise(worldPosition.xz * 0.018 + wind);
    float mediumNoise = fogValueNoise(worldPosition.xz * 0.047 - wind * 1.4);
    float detailNoise = fogValueNoise(worldPosition.xz * 0.110 + wind * 2.1);
    float patchField = broadNoise * 0.56 + mediumNoise * 0.32 + detailNoise * 0.12;
    float patchStart = 1.0 - GROUND_FOG_COVERAGE;
    return smoothstep(patchStart, min(patchStart + 0.16, 0.98), patchField);
}

float integrateGroundMist(vec3 cameraRelativeEnd, vec3 worldNormal, float skyLight, float rainStrength) {
    float fullRayLength = length(cameraRelativeEnd);
    float groundFacing = smoothstep(0.28, 0.78, worldNormal.y);
    if (fullRayLength < 2.0 || groundFacing <= 0.0001 || GROUND_FOG_STRENGTH <= 0.0001) {
        return 0.0;
    }

    vec3 rayDirection = cameraRelativeEnd / max(fullRayLength, 0.0001);
    float marchLength = min(fullRayLength, 20.0);
    float marchStart = fullRayLength - marchLength;
    float stepLength = marchLength / float(GROUND_FOG_SAMPLES);
    float opticalDepth = 0.0;
    vec3 surfaceWorldPosition = cameraPosition + cameraRelativeEnd;
    float outdoorFactor = smoothstep(0.08, 0.72, max(skyLight, lumavaleSkyExposure));
    float climateWeight = lumavaleBiomeDry + lumavaleBiomeRainy + lumavaleBiomeSnowy;
    float climateDensity =
        0.82 * lumavaleBiomeDry +
        1.18 * lumavaleBiomeRainy +
        1.08 * lumavaleBiomeSnowy;
    climateDensity /= max(climateWeight, 0.0001);
    climateDensity = mix(1.0, climateDensity, clamp(climateWeight, 0.0, 1.0));

    for (int sampleIndex = 0; sampleIndex < GROUND_FOG_SAMPLES; ++sampleIndex) {
        float sampleDistance = marchStart + (float(sampleIndex) + 0.5) * stepLength;
        vec3 sampleWorldPosition = cameraPosition + rayDirection * sampleDistance;
        float heightAboveSurface = dot(sampleWorldPosition - surfaceWorldPosition, worldNormal);
        float heightDensity = smoothstep(-0.18, 0.04, heightAboveSurface) *
            (1.0 - smoothstep(
                GROUND_FOG_HEIGHT * 0.55,
                GROUND_FOG_HEIGHT,
                heightAboveSurface
            ));
        float patchDensity = groundMistPatch(sampleWorldPosition);
        float density = heightDensity * patchDensity;
        density *= groundFacing * outdoorFactor * climateDensity;
        density *= mix(1.0, 1.22, rainStrength);
        opticalDepth += density * stepLength * 0.055;
    }

    float distanceVisibility = smoothstep(4.0, 14.0, fullRayLength);
    return clamp(
        (1.0 - exp(-opticalDepth * GROUND_FOG_STRENGTH)) * distanceVisibility,
        0.0,
        0.72
    );
}

float computeCaveFog(float distanceFromCamera, float pixelSkyLight) {
    float caveFactor = clamp(lumavaleCaveFactor, 0.0, 1.0);
    float openingRelief = mix(1.0, 0.30, pow(clamp(pixelSkyLight, 0.0, 1.0), 2.0));
    float distanceFog = 1.0 - exp(-distanceFromCamera * 0.030);
    return clamp(distanceFog * caveFactor * openingRelief * CAVE_FOG_STRENGTH, 0.0, 0.88);
}

#endif

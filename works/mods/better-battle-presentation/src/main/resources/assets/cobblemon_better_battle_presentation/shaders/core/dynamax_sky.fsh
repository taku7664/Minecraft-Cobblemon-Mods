#version 150

in vec2 texCoord0;

uniform float EffectTimeSeconds;
uniform float EffectStrength;
uniform mat4 InverseViewProjection;
uniform vec3 WorldUp;

out vec4 fragColor;

float hash21(vec2 point) {
    return fract(sin(dot(point, vec2(127.1, 311.7))) * 43758.5453);
}

float valueNoise(vec2 point) {
    vec2 cell = floor(point);
    vec2 local = fract(point);
    vec2 curve = local * local * (3.0 - 2.0 * local);
    float a = hash21(cell);
    float b = hash21(cell + vec2(1.0, 0.0));
    float c = hash21(cell + vec2(0.0, 1.0));
    float d = hash21(cell + vec2(1.0, 1.0));
    return mix(mix(a, b, curve.x), mix(c, d, curve.x), curve.y);
}

float fbm(vec2 point) {
    float value = 0.0;
    float amplitude = 0.55;
    mat2 rotation = mat2(0.80, -0.60, 0.60, 0.80);
    for (int octave = 0; octave < 5; octave++) {
        value += valueNoise(point) * amplitude;
        point = rotation * point * 2.03 + vec2(13.7, 5.2);
        amplitude *= 0.5;
    }
    return value;
}

void main() {
    vec2 ndc = texCoord0 * 2.0 - 1.0;
    vec4 nearPoint = InverseViewProjection * vec4(ndc, -1.0, 1.0);
    vec4 farPoint = InverseViewProjection * vec4(ndc, 1.0, 1.0);
    vec3 nearWorld = nearPoint.xyz / max(abs(nearPoint.w), 0.0001);
    vec3 farWorld = farPoint.xyz / max(abs(farPoint.w), 0.0001);
    vec3 direction = normalize(farWorld - nearWorld);
    vec3 up = normalize(WorldUp);

    float altitude = clamp(dot(direction, up), -1.0, 1.0);
    float azimuth = atan(direction.z, direction.x);
    float polarRadius = acos(altitude);
    vec2 heading = normalize(vec2(direction.x, direction.z) + vec2(0.0001));

    // The renderer wraps EffectTimeSeconds every 180 seconds. Wave motion completes
    // three cycles while the slower world-up orbit completes one, so both wrap cleanly.
    float cycleTime = EffectTimeSeconds * 0.104719755;
    float orbitCycleTime = EffectTimeSeconds * 0.034906585;
    vec2 slowClock = vec2(cos(cycleTime), sin(cycleTime));

    float horizonPresence = smoothstep(-0.24, 0.015, altitude);

    vec2 domePoint = heading * (1.4 + polarRadius * 2.7);
    float stormWarp = fbm(domePoint * 0.82 + slowClock * 0.24);
    float stormPhase = azimuth * 4.0 - polarRadius * 10.5 - orbitCycleTime * 4.0 + stormWarp * 4.6;
    float tornEdge = 1.0 - abs(sin(stormPhase));
    float stormArm = smoothstep(0.30, 0.78, tornEdge + (stormWarp - 0.5) * 0.70);
    float brokenMass = smoothstep(0.32, 0.78, fbm(domePoint * 1.45 + slowClock * 0.31));
    float stormOcclusion = clamp(stormArm * 0.72 + brokenMass * 0.48, 0.0, 1.0);
    float vortexEye = 1.0 - smoothstep(0.08, 0.34, polarRadius);

    float auroraOrbitAngle = orbitCycleTime;
    float orbitCos = cos(auroraOrbitAngle);
    float orbitSin = sin(auroraOrbitAngle);
    mat2 orbitRotation = mat2(orbitCos, -orbitSin, orbitSin, orbitCos);
    vec2 orbitHeading = orbitRotation * heading;
    float orbitAzimuth = atan(orbitHeading.y, orbitHeading.x);
    vec2 warpClock = vec2(cos(cycleTime * 3.0), sin(cycleTime * 3.0));
    vec2 curtainPoint = orbitHeading * 3.2 + vec2(altitude * 1.7, altitude * 3.8);
    float curtainWarp = fbm(curtainPoint + warpClock * 0.38);

    float primarySpiralPhase = orbitAzimuth - polarRadius * 2.8 + curtainWarp * 0.85;
    float secondarySpiralPhase = orbitAzimuth - polarRadius * 2.1 + 2.35 + curtainWarp * 0.55;
    float primarySpiralWave = cos(primarySpiralPhase);
    float secondarySpiralWave = cos(secondarySpiralPhase);
    float spiralWave = primarySpiralWave * 0.78 + secondarySpiralWave * 0.22;
    float spiralDisplacement = spiralWave * 0.07;
    float primaryRibbon = smoothstep(0.18, 0.84, primarySpiralWave);
    float secondaryRibbon = smoothstep(0.46, 0.90, secondarySpiralWave) * 0.68;
    float spiralRibbon = max(primaryRibbon, secondaryRibbon);

    float travelingWave = sin(
        orbitAzimuth * 5.0
        + altitude * 8.5
        - cycleTime * 12.0
        + curtainWarp * 4.5
    );
    float counterWave = sin(
        orbitAzimuth * 2.0
        - altitude * 5.0
        + cycleTime * 8.0
        + curtainWarp * 2.0
    );
    float curtainDisplacement = spiralDisplacement
        + travelingWave * 0.06
        + counterWave * 0.03;
    float displacedAltitude = altitude + curtainDisplacement;

    float lowerEdge = -0.09
        + sin(orbitAzimuth * 2.0 - cycleTime * 6.0 + curtainWarp * 3.0) * 0.10;
    float upperEdge = 0.76
        + sin(orbitAzimuth * 3.0 + cycleTime * 4.0 + curtainWarp * 2.4) * 0.15;
    float curtainHeight = smoothstep(lowerEdge, lowerEdge + 0.070, displacedAltitude)
        * (1.0 - smoothstep(upperEdge - 0.18, upperEdge, displacedAltitude));
    float sectorNoise = fbm(orbitHeading * 2.35 + warpClock * 0.30);
    float sectorPresence = 0.48 + smoothstep(0.28, 0.72, sectorNoise) * 0.52;

    float foldingWave = 0.5 + 0.5 * sin(
        orbitAzimuth * 6.0
        + displacedAltitude * 7.5
        - cycleTime * 10.0
        + curtainWarp * 5.4
    );
    float verticalRipple = 0.5 + 0.5 * sin(
        displacedAltitude * 20.0
        - cycleTime * 14.0
        + curtainWarp * 3.6
    );
    float broadCurtain = smoothstep(
        0.20,
        0.76,
        foldingWave * 0.48 + verticalRipple * 0.20 + spiralRibbon * 0.32
    );
    float auroraCurtain = curtainHeight * sectorPresence
        * smoothstep(
            0.54,
            0.90,
            foldingWave * 0.50 + verticalRipple * 0.16 + spiralRibbon * 0.34
        );
    float auroraGlow = curtainHeight * sectorPresence
        * max(broadCurtain, spiralRibbon * 0.82);

    vec3 darkSky = vec3(0.010, 0.0015, 0.007);
    vec3 stormBurgundy = vec3(0.105, 0.004, 0.020);
    vec3 deepCrimson = vec3(0.42, 0.006, 0.075);
    vec3 hotMagenta = vec3(1.00, 0.018, 0.30);

    vec3 color = mix(darkSky, stormBurgundy, stormOcclusion * 0.72);
    color = mix(color, deepCrimson, auroraGlow * 0.72);
    color = mix(color, hotMagenta, auroraCurtain * 0.94);
    color *= 1.0 - vortexEye * 0.88;

    float opacity = 0.86 + stormOcclusion * 0.09 + auroraGlow * 0.035;
    float alpha = EffectStrength * horizonPresence * clamp(opacity, 0.0, 0.975);
    fragColor = vec4(color, alpha);
}

#version 150

uniform sampler2D SceneSampler;
uniform sampler2D BackgroundSampler;
uniform sampler2D DepthSampler;
uniform float GameTime;
uniform float EffectAgeSeconds;
uniform float EffectStrength;
uniform mat4 InverseViewProjection;
uniform vec3 ArenaCenterRelative;
uniform vec2 ArenaOpponentDirection;
uniform vec3 CameraWorldPosition;

in vec2 TexCoord;
out vec4 fragColor;

const float SKY_DEPTH_THRESHOLD = 0.99998;
const float ARENA_INNER_RADIUS = 5.5;
const float ARENA_OUTER_RADIUS = 13.0;
const float RING_SPACING = 1.8;
const float BASE_TERRAIN_TINT_STRENGTH = 0.61;
const float CENTER_TERRAIN_TINT_STRENGTH = 0.92;
const float LED_CELL_SPACING = 0.58;
const float LED_CORE_INNER_RADIUS = 0.19;
const float LED_CORE_OUTER_RADIUS = 0.40;
const float LED_HALO_RADIUS = 0.52;
const float LED_PATTERN_PERIOD_SECONDS = 3.6;
const vec3 HOLOGRAM_CYAN = vec3(0.055, 0.82, 1.0);
const vec3 CENTER_HOLOGRAM_WHITE = vec3(0.92, 0.95, 1.0);
const vec3 ARENA_LED_SILVER = vec3(0.68, 0.75, 0.82);
const vec3 ARENA_LED_BLUE = vec3(0.22, 0.54, 0.92);
const vec3 POKEBALL_RED = vec3(1.0, 0.10, 0.055);
const vec3 POKEBALL_CHARCOAL = vec3(0.035, 0.042, 0.050);

float orderedDither(vec2 pixel) {
    vec2 cell = mod(floor(pixel), 4.0);
    float index = cell.x + cell.y * 4.0;
    float threshold = 0.0;
    if (index == 0.0) threshold = 0.0;
    else if (index == 1.0) threshold = 8.0;
    else if (index == 2.0) threshold = 2.0;
    else if (index == 3.0) threshold = 10.0;
    else if (index == 4.0) threshold = 12.0;
    else if (index == 5.0) threshold = 4.0;
    else if (index == 6.0) threshold = 14.0;
    else if (index == 7.0) threshold = 6.0;
    else if (index == 8.0) threshold = 3.0;
    else if (index == 9.0) threshold = 11.0;
    else if (index == 10.0) threshold = 1.0;
    else if (index == 11.0) threshold = 9.0;
    else if (index == 12.0) threshold = 15.0;
    else if (index == 13.0) threshold = 7.0;
    else if (index == 14.0) threshold = 13.0;
    else threshold = 5.0;
    return (threshold + 0.5) / 16.0;
}

float signalNoise(vec2 pixel, float time) {
    vec2 cell = floor(pixel * vec2(0.19, 0.31));
    return fract(sin(dot(cell + time * 31.0, vec2(12.9898, 78.233))) * 43758.5453);
}

float cellHash(vec2 cell) {
    return fract(sin(dot(cell, vec2(127.1, 311.7))) * 43758.5453);
}

float softScan(float distanceToLine, float width) {
    float normalizedDistance = distanceToLine / width;
    return exp(-normalizedDistance * normalizedDistance);
}

void main() {
    vec4 scene = texture(SceneSampler, TexCoord);
    float depth = texture(DepthSampler, TexCoord).r;
    if (depth >= SKY_DEPTH_THRESHOLD || EffectStrength <= 0.001) {
        fragColor = scene;
        return;
    }

    vec4 background = texture(BackgroundSampler, TexCoord);
    vec4 clipPosition = vec4(TexCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 reconstructed = InverseViewProjection * clipPosition;
    vec3 worldRelative = reconstructed.xyz / max(abs(reconstructed.w), 0.00001);
    vec3 worldPosition = worldRelative + CameraWorldPosition;
    vec3 arenaCenterWorld = ArenaCenterRelative + CameraWorldPosition;
    vec3 worldDelta = worldPosition - arenaCenterWorld;
    float arenaDistance = distance(worldRelative.xz, ArenaCenterRelative.xz);
    float spatialStrength = smoothstep(ARENA_INNER_RADIUS, ARENA_OUTER_RADIUS, arenaDistance);
    float arenaAxis = dot(worldDelta.xz, ArenaOpponentDirection);
    float opponentHalf = smoothstep(-0.32, 0.32, arenaAxis);

    vec3 surfaceDx = dFdx(worldPosition);
    vec3 surfaceDy = dFdy(worldPosition);
    vec3 surfaceNormal = normalize(cross(surfaceDx, surfaceDy) + vec3(0.000001));
    float surfaceLight = 0.76 + 0.24 * abs(dot(surfaceNormal, normalize(vec3(0.34, 0.82, 0.46))));
    float surfaceCurvature = clamp(length(dFdx(surfaceNormal)) + length(dFdy(surfaceNormal)), 0.0, 1.0);
    float surfaceUpMask = smoothstep(0.58, 0.90, abs(surfaceNormal.y));
    float arenaHeightMask = 1.0 - smoothstep(1.35, 2.40, abs(worldDelta.y));
    float centerRadialMask = 1.0 - smoothstep(5.0, ARENA_INNER_RADIUS, arenaDistance);
    float centerBallMask = centerRadialMask * surfaceUpMask * arenaHeightMask;

    float originalLuminance = dot(scene.rgb, vec3(0.299, 0.587, 0.114));
    float surfaceDistance = length(worldDelta);
    float ringOffset = mod(
        surfaceDistance - GameTime * 72.0 + RING_SPACING * 0.5,
        RING_SPACING
    ) - RING_SPACING * 0.5;
    float ringDistance = abs(ringOffset);
    float gaussianCore = softScan(ringDistance, 0.055);
    float gaussianHalo = softScan(ringDistance, 0.19);
    float risingScan = gaussianCore * 0.58 + gaussianHalo * 0.24;
    float noise = signalNoise(gl_FragCoord.xy, GameTime) - 0.5;

    float centerWhiteness = 1.0 - spatialStrength;
    vec3 hologramTint = mix(HOLOGRAM_CYAN, CENTER_HOLOGRAM_WHITE, centerWhiteness);
    vec3 pokeballCenterTint = mix(CENTER_HOLOGRAM_WHITE, POKEBALL_RED, opponentHalf * 0.34);
    hologramTint = mix(hologramTint, pokeballCenterTint, centerBallMask);
    vec3 textureDetail = mix(vec3(originalLuminance), scene.rgb, 0.42) * surfaceLight;
    float terrainTintStrength = mix(
        BASE_TERRAIN_TINT_STRENGTH,
        CENTER_TERRAIN_TINT_STRENGTH,
        centerBallMask
    );
    vec3 hologram = mix(textureDetail, hologramTint, terrainTintStrength);
    hologram += hologramTint * (0.07 + risingScan * 0.21);
    hologram += hologramTint * surfaceCurvature * 0.13;
    hologram += noise * 0.035;

    float dither = orderedDither(gl_FragCoord.xy);
    float backgroundReveal = step(dither, 0.14 + noise * 0.025);
    backgroundReveal *= spatialStrength;
    vec3 projectedTerrain = mix(hologram, background.rgb, backgroundReveal);

    // The opponent-facing half is red, the player-facing half stays white, and a translucent
    // charcoal band divides the teams without replacing the underlying terrain geometry.
    float centerBandCore = 1.0 - smoothstep(0.20, 0.34, abs(arenaAxis));
    float centerBandMask = centerBandCore * centerBallMask;
    float buttonOuterMask = (1.0 - smoothstep(0.72, 0.88, arenaDistance)) * centerBallMask;
    float buttonCoreMask = (1.0 - smoothstep(0.36, 0.50, arenaDistance)) * centerBallMask;
    float buttonRingMask = buttonOuterMask * (1.0 - buttonCoreMask);
    vec3 bandColor = mix(textureDetail, POKEBALL_CHARCOAL, 0.82);
    projectedTerrain = mix(projectedTerrain, bandColor, centerBandMask * 0.72);
    projectedTerrain = mix(projectedTerrain, POKEBALL_CHARCOAL, buttonRingMask * 0.88);
    projectedTerrain = mix(projectedTerrain, CENTER_HOLOGRAM_WHITE, buttonCoreMask * 0.78);

    // A staggered LED matrix follows upward-facing world terrain inside the arena center.
    float ledRow = floor(worldDelta.z / LED_CELL_SPACING + 0.5);
    float ledRowOffset = mod(abs(ledRow), 2.0) * LED_CELL_SPACING * 0.5;
    float ledColumn = floor((worldDelta.x - ledRowOffset) / LED_CELL_SPACING + 0.5);
    vec2 ledCellId = vec2(ledColumn, ledRow);
    vec2 ledCellCenter = vec2(
        ledColumn * LED_CELL_SPACING + ledRowOffset,
        ledRow * LED_CELL_SPACING
    );
    float ledCellDistance = length(worldDelta.xz - ledCellCenter);
    float ledCore = 1.0 - smoothstep(LED_CORE_INNER_RADIUS, LED_CORE_OUTER_RADIUS, ledCellDistance);
    float ledHalo = softScan(ledCellDistance, LED_HALO_RADIUS);
    float ledRandom = cellHash(ledCellId);

    // Repeat the full three-tap center-out pattern instead of playing it only once at battle start.
    float patternTime = mod(max(EffectAgeSeconds, 0.0), LED_PATTERN_PERIOD_SECONDS);
    float cellAge = patternTime - arenaDistance * 0.105 - ledRandom * 0.075;
    float repeatingTap =
        softScan(abs(cellAge - 0.02), 0.040) * 0.70 +
        softScan(abs(cellAge - 0.15), 0.037) * 0.82 +
        softScan(abs(cellAge - 0.29), 0.045);
    float settledLight = smoothstep(0.55, 0.85, EffectAgeSeconds) * 0.16;
    float ledIntensity = clamp(max(repeatingTap, settledLight), 0.0, 1.0);

    float centerLedMask = centerBallMask;
    float regularLedMask = centerLedMask * (1.0 - centerBandCore) * (1.0 - buttonOuterMask);
    vec3 playerLedColor = mix(CENTER_HOLOGRAM_WHITE, ARENA_LED_SILVER, 0.14 + ledRandom * 0.10);
    vec3 opponentLedColor = mix(POKEBALL_RED, vec3(1.0, 0.34, 0.08), ledRandom * 0.18);
    vec3 ledColor = mix(playerLedColor, opponentLedColor, opponentHalf);
    vec3 ledLighting = ledColor * ledCore * (0.12 + ledIntensity * 0.62);
    ledLighting += ledColor * ledHalo * ledIntensity * 0.10;
    float ballRimMask = softScan(abs(arenaDistance - 5.10), 0.075) * surfaceUpMask * arenaHeightMask;
    vec3 buttonLighting = CENTER_HOLOGRAM_WHITE * buttonCoreMask * (0.16 + ledIntensity * 0.50);
    buttonLighting += ARENA_LED_SILVER * buttonRingMask * ledIntensity * 0.18;
    vec3 rimLighting = CENTER_HOLOGRAM_WHITE * ballRimMask * (0.10 + ledIntensity * 0.18);

    vec3 hologramTerrain = mix(scene.rgb, projectedTerrain, clamp(EffectStrength, 0.0, 1.0));
    vec3 finalColor = hologramTerrain;
    finalColor += ledLighting * EffectStrength * regularLedMask;
    finalColor += (buttonLighting + rimLighting) * EffectStrength;
    fragColor = vec4(clamp(finalColor, 0.0, 1.0), scene.a);
}

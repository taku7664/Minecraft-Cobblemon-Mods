#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float GameTime;

in vec3 worldPosition;
in vec3 modelNormal;
in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

// Scanline geometry is borrowed from the model hologram, its colour from the terrain hologram: a
// wide gaussian halo in hologram cyan with a narrow white-hot core, the same core/halo split the
// terrain rings use.
const float SCAN_POSITION_JITTER = 0.022;
const float SINGLE_SCAN_WIDTH = 0.085;
const float SCAN_CORE_WIDTH = 0.026;
const vec3 HOLOGRAM_CYAN = vec3(0.055, 0.82, 1.0);
const vec3 CENTER_HOLOGRAM_WHITE = vec3(0.92, 0.95, 1.0);
const float BASE_TERRAIN_TINT_STRENGTH = 0.61;
// Fraction of pixels the ordered dither drops so the projection reads as a pixel grid rather than
// flat translucency. The terrain shader blends in its BackgroundSampler here; an entity pass has no
// such sampler, so the same cells are discarded and the real scene shows through instead.
const float DITHER_REVEAL = 0.14;

float randomNoise(vec2 point) {
    return fract(sin(dot(point, vec2(12.9898, 78.233))) * 43758.5453);
}

float softScan(float distanceToLine, float width) {
    float normalizedDistance = distanceToLine / width;
    return exp(-normalizedDistance * normalizedDistance);
}

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

// Distance to the nearest travelling line, in cycles. Each cycle jitters its own centre so the
// spacing never reads as a fixed metronome.
float scanDistance(float coordinate) {
    float cycle = floor(coordinate);
    float phase = fract(coordinate);
    float center = 0.5 + (randomNoise(vec2(cycle, 7.13)) * 2.0 - 1.0) * SCAN_POSITION_JITTER;
    float distanceToLine = abs(phase - center);
    return min(distanceToLine, 1.0 - distanceToLine);
}

void main() {
    float pulseClock = floor(GameTime * 12000.0);
    float sequenceId = floor(pulseClock / 8.0);
    float sequenceStep = mod(pulseClock, 8.0);
    float pulseIndex = floor(sequenceStep / 2.0);
    float pulseCount = 2.0 + step(0.48, randomNoise(vec2(sequenceId, 11.7)));
    float eventActive = step(0.72, randomNoise(vec2(sequenceId, 5.31)));
    float pulseActive = (1.0 - step(0.5, mod(sequenceStep, 2.0)))
        * (1.0 - step(pulseCount, pulseIndex));

    float regionStart = floor(randomNoise(vec2(sequenceId, 23.9)) * 3.0);
    float regionDirection = 1.0 + step(0.5, randomNoise(vec2(sequenceId, 41.3)));
    float regionSequence = mod(regionStart + pulseIndex * regionDirection, 3.0);
    float fragmentRegion = mod(floor(worldPosition.y * 1.55), 3.0);
    float regionActive = 1.0 - step(0.25, abs(fragmentRegion - regionSequence));
    float glitchWindow = eventActive * pulseActive * regionActive;

    float signalRow = floor(worldPosition.y * 12.0);
    float rowActive = step(0.36, randomNoise(vec2(signalRow, sequenceId * 1.91 + pulseIndex)));
    float channelShift = (
        randomNoise(vec2(sequenceId * 2.73, signalRow * 3.17 + pulseIndex)) * 2.0 - 1.0
    ) * glitchWindow * rowActive * 0.0045;

    vec2 redUv = clamp(texCoord0 + vec2(channelShift, 0.0), vec2(0.0), vec2(1.0));
    vec2 blueUv = clamp(texCoord0 - vec2(channelShift, 0.0), vec2(0.0), vec2(1.0));
    vec4 baseSource = texture(Sampler0, texCoord0);
    vec4 redSource = texture(Sampler0, redUv);
    vec4 blueSource = texture(Sampler0, blueUv);
    vec4 source = vec4(redSource.r, baseSource.g, blueSource.b, baseSource.a);
    if (source.a < 0.1) {
        discard;
    }

    float scanTravel = GameTime * 1350.0;
    float scanCoordinate = (worldPosition.y * 32.0 - scanTravel) / 16.3362818;
    float distanceToScan = scanDistance(scanCoordinate);
    float scanHalo = softScan(distanceToScan, SINGLE_SCAN_WIDTH);
    float scanCore = softScan(distanceToScan, SCAN_CORE_WIDTH);
    float scanline = scanHalo * 0.58 + scanCore * 0.42;

    float fineLines = 0.5 + 0.5 * sin(worldPosition.y * 138.0);
    float noise = randomNoise(
        worldPosition.xz * vec2(17.0, 23.0) + vec2(GameTime * 311.0, GameTime * 197.0)
    );
    float staticNoise = randomNoise(
        floor(worldPosition.xy * vec2(12.0, 12.0)) + vec2(sequenceId + pulseIndex, sequenceId * 0.37)
    ) * 2.0 - 1.0;
    float signalBreak = glitchWindow * rowActive;

    // The terrain shader lights its surfaces from a rebuilt normal and brightens the creases where
    // that normal changes fastest; both are what give the blocks their volume, so the model does the
    // same from its own normal.
    float surfaceLight = 0.76 + 0.24 * abs(dot(modelNormal, normalize(vec3(0.34, 0.82, 0.46))));
    float surfaceCurvature = clamp(length(dFdx(modelNormal)) + length(dFdy(modelNormal)), 0.0, 1.0);

    float luminance = dot(source.rgb, vec3(0.299, 0.587, 0.114));
    vec3 hologramTint = mix(HOLOGRAM_CYAN, CENTER_HOLOGRAM_WHITE, clamp(scanCore, 0.0, 1.0));
    vec3 textureDetail = mix(vec3(luminance), source.rgb, 0.42) * surfaceLight;
    vec3 hologram = mix(textureDetail, hologramTint, BASE_TERRAIN_TINT_STRENGTH);
    hologram += hologramTint * (0.07 + scanline * 0.30 + fineLines * 0.025 + noise * 0.018);
    hologram += hologramTint * surfaceCurvature * 0.13;
    hologram += vec3(staticNoise * signalBreak * 0.15);

    // Screen-space on purpose: the dropped cells are the projector's pixel grid, so they belong to
    // the display rather than to the body. Every other pattern above stays in world space.
    float ditherNoise = randomNoise(floor(gl_FragCoord.xy) + vec2(sequenceId, pulseIndex));
    if (orderedDither(gl_FragCoord.xy) < DITHER_REVEAL + (ditherNoise - 0.5) * 0.05) {
        discard;
    }

    float alpha = source.a * (
        0.60 + scanline * 0.16 + fineLines * 0.025 + noise * 0.025 - abs(staticNoise) * signalBreak * 0.08
    );
    fragColor = vec4(
        clamp(hologram * vertexColor.rgb * ColorModulator.rgb, 0.0, 1.0),
        clamp(alpha * ColorModulator.a, 0.0, 0.82)
    );
}

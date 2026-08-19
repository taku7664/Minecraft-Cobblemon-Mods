#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float GameTime;

in vec3 worldPosition;
in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

const float SCAN_POSITION_JITTER = 0.022;
const float SINGLE_SCAN_WIDTH = 0.085;
const vec3 SHADOW_DARK_YELLOW = vec3(0.38, 0.31, 0.075);

float randomNoise(vec2 point) {
    return fract(sin(dot(point, vec2(12.9898, 78.233))) * 43758.5453);
}

float singleScanLine(float coordinate) {
    float cycle = floor(coordinate);
    float phase = fract(coordinate);
    float center = 0.5 + (randomNoise(vec2(cycle, 7.13)) * 2.0 - 1.0) * SCAN_POSITION_JITTER;
    float distanceToLine = abs(phase - center);
    distanceToLine = min(distanceToLine, 1.0 - distanceToLine);
    float normalizedDistance = distanceToLine / SINGLE_SCAN_WIDTH;
    return exp(-normalizedDistance * normalizedDistance);
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
    float scanline = singleScanLine(scanCoordinate);
    float fineLines = 0.5 + 0.5 * sin(worldPosition.y * 138.0);
    float noise = randomNoise(
        worldPosition.xz * vec2(17.0, 23.0) + vec2(GameTime * 311.0, GameTime * 197.0)
    );
    float staticNoise = randomNoise(
        floor(worldPosition.xy * vec2(12.0, 12.0)) + vec2(sequenceId + pulseIndex, sequenceId * 0.37)
    ) * 2.0 - 1.0;
    float signalBreak = glitchWindow * rowActive;
    float luminance = dot(source.rgb, vec3(0.299, 0.587, 0.114));

    vec3 textureDetail = mix(vec3(luminance), source.rgb, 0.32);
    vec3 hologram = mix(textureDetail, SHADOW_DARK_YELLOW, 0.72);
    hologram += SHADOW_DARK_YELLOW * (0.10 + scanline * 0.34 + fineLines * 0.025 + noise * 0.018);
    hologram += vec3(staticNoise * signalBreak * 0.15);

    float alpha = source.a * (
        0.60 + scanline * 0.16 + fineLines * 0.025 + noise * 0.025 - abs(staticNoise) * signalBreak * 0.08
    );
    fragColor = vec4(
        clamp(hologram * vertexColor.rgb * ColorModulator.rgb, 0.0, 1.0),
        clamp(alpha * ColorModulator.a, 0.0, 0.82)
    );
}

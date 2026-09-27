#version 150

in vec3 Position;
in vec4 Color;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 CameraWorldPosition;
uniform float GameTime;

out vec3 worldPosition;
out vec4 vertexColor;
out vec2 texCoord0;

float randomNoise(vec2 point) {
    return fract(sin(dot(point, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    worldPosition = Position + CameraWorldPosition;
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
    float vertexRegion = mod(floor(worldPosition.y * 1.55), 3.0);
    float regionActive = 1.0 - step(0.25, abs(vertexRegion - regionSequence));

    float shiftDirection = randomNoise(vec2(sequenceId * 2.73, pulseIndex * 3.17)) * 2.0 - 1.0;
    float glitchOffset = eventActive * pulseActive * regionActive * shiftDirection * 0.042;
    vec3 displacedPosition = Position + vec3(glitchOffset, 0.0, 0.0);

    gl_Position = ProjMat * ModelViewMat * vec4(displacedPosition, 1.0);
    vertexColor = vec4(Color.rgb, 1.0);
    texCoord0 = UV0;
}

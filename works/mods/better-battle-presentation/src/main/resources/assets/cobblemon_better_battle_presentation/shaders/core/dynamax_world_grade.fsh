#version 150

uniform float EffectStrength;

out vec4 fragColor;

void main() {
    float alpha = clamp(EffectStrength * 0.24, 0.0, 0.24);
    fragColor = vec4(vec3(0.22, 0.01, 0.02), alpha);
}

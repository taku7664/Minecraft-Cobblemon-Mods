vec3 toLinear(vec3 color) {
    return pow(max(color, vec3(0.0)), vec3(2.2));
}

vec3 toSrgb(vec3 color) {
    return pow(max(color, vec3(0.0)), vec3(1.0 / 2.2));
}

float luminance(vec3 color) {
    return dot(color, vec3(0.2126, 0.7152, 0.0722));
}

vec3 adjustSaturation(vec3 color, float amount) {
    return mix(vec3(luminance(color)), color, amount);
}

vec3 softToneMap(vec3 color) {
    color = max(color, vec3(0.0));
    return color * (2.15 * color + 0.08) / (color * (2.05 * color + 0.72) + 0.12);
}

vec3 lumaValeGrade(vec3 color, float saturation) {
    color = softToneMap(color);
    color = toSrgb(color);
    color = adjustSaturation(color, saturation);
    float highlight = smoothstep(0.45, 1.25, luminance(color));
    color *= mix(vec3(0.985, 1.01, 1.025), vec3(1.035, 1.01, 0.965), highlight);
    color = (color - 0.5) * 0.92 + 0.515;
    return clamp(color, 0.0, 1.0);
}

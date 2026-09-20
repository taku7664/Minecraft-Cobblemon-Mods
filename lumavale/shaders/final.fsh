#version 330 compatibility

#include "/lib/settings.glsl"
#include "/lib/color.glsl"

uniform sampler2D colortex0;
uniform float viewWidth;
uniform float viewHeight;

in vec2 texcoord;

vec3 bloomSample(vec2 uv, vec2 pixelSize) {
    vec3 bloom = vec3(0.0);
    const vec2 directions[8] = vec2[8](
        vec2(1.0, 0.0), vec2(-1.0, 0.0),
        vec2(0.0, 1.0), vec2(0.0, -1.0),
        vec2(0.707, 0.707), vec2(-0.707, 0.707),
        vec2(0.707, -0.707), vec2(-0.707, -0.707)
    );

    for (int index = 0; index < 8; ++index) {
        vec3 sampleColor = texture(colortex0, uv + directions[index] * pixelSize * 2.0).rgb;
        float brightWeight = smoothstep(0.72, 1.55, luminance(sampleColor));
        bloom += sampleColor * brightWeight;
    }
    return bloom * 0.125;
}

void main() {
    vec3 color = texture(colortex0, texcoord).rgb;
    vec2 pixelSize = vec2(1.0 / viewWidth, 1.0 / viewHeight);
    color += bloomSample(texcoord, pixelSize) * BLOOM_STRENGTH;
    color = lumaValeGrade(color, SATURATION);
    gl_FragColor = vec4(color, 1.0);
}

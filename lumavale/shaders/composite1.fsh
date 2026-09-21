#version 330 compatibility

#include "/lib/color.glsl"

uniform sampler2D colortex0;

in vec2 texcoord;

/*
const int colortex3Format = R11F_G11F_B10F;
*/

/* DRAWBUFFERS:3 */

void main() {
    const vec2 directions[8] = vec2[8](
        vec2(1.0, 0.0), vec2(-1.0, 0.0),
        vec2(0.0, 1.0), vec2(0.0, -1.0),
        vec2(0.707, 0.707), vec2(-0.707, 0.707),
        vec2(0.707, -0.707), vec2(-0.707, -0.707)
    );

    vec2 sourcePixelSize = 1.0 / vec2(textureSize(colortex0, 0));
    vec3 bloom = vec3(0.0);
    for (int index = 0; index < 8; ++index) {
        vec3 sampleColor = texture(colortex0, texcoord + directions[index] * sourcePixelSize * 2.0).rgb;
        float brightWeight = smoothstep(0.72, 1.55, luminance(sampleColor));
        bloom += sampleColor * brightWeight;
    }

    gl_FragData[0] = vec4(bloom * 0.125, 1.0);
}

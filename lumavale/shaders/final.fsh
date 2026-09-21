#version 330 compatibility

#include "/lib/settings.glsl"
#include "/lib/color.glsl"

uniform sampler2D colortex0;
uniform sampler2D colortex3;

in vec2 texcoord;

void main() {
    vec3 color = texture(colortex0, texcoord).rgb;
    color += texture(colortex3, texcoord).rgb * BLOOM_STRENGTH;
    color = lumaValeGrade(color, SATURATION);
    gl_FragColor = vec4(color, 1.0);
}

#version 330 compatibility

#include "/lib/distort.glsl"

out vec2 texcoord;
out vec4 vertexColor;

void main() {
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    vertexColor = gl_Color;

    gl_Position = ftransform();
    gl_Position.xyz = distortShadowClipPosition(gl_Position.xyz);
}

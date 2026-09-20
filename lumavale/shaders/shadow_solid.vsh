#version 330 compatibility

#include "/lib/distort.glsl"

void main() {
    gl_Position = ftransform();
    gl_Position.xyz = distortShadowClipPosition(gl_Position.xyz);
}

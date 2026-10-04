#version 330 compatibility

in vec4 vertexColor;

/* DRAWBUFFERS:0 */

void main() {
    if (vertexColor.a <= 0.001) {
        discard;
    }
    gl_FragData[0] = vertexColor;
}

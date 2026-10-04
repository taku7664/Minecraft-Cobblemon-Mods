#version 330 compatibility

uniform sampler2D gtexture;

in vec2 texcoord;
in vec4 vertexColor;

void main() {
    vec4 color = texture(gtexture, texcoord) * vertexColor;
    if (color.a < 0.1) {
        discard;
    }
}

#version 330 compatibility

uniform sampler2D gtexture;
uniform float alphaTestRef = 0.1;

in vec2 texcoord;
in vec4 vertexColor;

/* DRAWBUFFERS:0 */

void main() {
    vec4 albedo = texture(gtexture, texcoord) * vertexColor;
    if (albedo.a < alphaTestRef) {
        discard;
    }
    gl_FragData[0] = albedo;
}

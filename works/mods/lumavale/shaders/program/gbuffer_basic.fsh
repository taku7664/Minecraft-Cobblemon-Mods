in vec2 lightcoord;
in vec4 vertexColor;
in vec3 worldNormal;

/* DRAWBUFFERS:012 */

void main() {
    if (vertexColor.a <= 0.001) {
        discard;
    }

    gl_FragData[0] = vertexColor;
    gl_FragData[1] = vec4(clamp(lightcoord, 0.0, 1.0), 0.0, 1.0);
    gl_FragData[2] = vec4(normalize(worldNormal) * 0.5 + 0.5, 1.0);
}

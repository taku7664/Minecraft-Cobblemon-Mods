uniform sampler2D gtexture;
uniform float alphaTestRef = 0.1;

in vec2 texcoord;
in vec2 lightcoord;
in vec4 vertexColor;
in vec3 worldNormal;

/* DRAWBUFFERS:012 */

void main() {
    vec4 albedo = texture(gtexture, texcoord) * vertexColor;
    if (albedo.a < alphaTestRef) {
        discard;
    }

#ifdef EMISSIVE_PASS
    vec2 storedLight = vec2(1.0);
#else
    vec2 storedLight = clamp(lightcoord, 0.0, 1.0);
#endif

    gl_FragData[0] = albedo;
    gl_FragData[1] = vec4(storedLight, 0.0, 1.0);
    gl_FragData[2] = vec4(normalize(worldNormal) * 0.5 + 0.5, 1.0);
}

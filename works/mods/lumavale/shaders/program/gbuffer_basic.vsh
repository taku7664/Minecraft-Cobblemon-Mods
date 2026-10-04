uniform mat4 gbufferModelViewInverse;

out vec2 lightcoord;
out vec4 vertexColor;
out vec3 worldNormal;

void main() {
    gl_Position = ftransform();
    lightcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
    vertexColor = gl_Color;

    worldNormal = normalize(mat3(gbufferModelViewInverse) * (gl_NormalMatrix * gl_Normal));
}

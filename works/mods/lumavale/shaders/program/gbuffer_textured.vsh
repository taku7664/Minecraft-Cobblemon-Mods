uniform mat4 gbufferModelViewInverse;

out vec2 texcoord;
out vec2 lightcoord;
out vec4 vertexColor;
out vec3 worldNormal;
#ifdef SEE_THROUGH
out vec3 seeThroughPos;
#endif

void main() {
    gl_Position = ftransform();
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    lightcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
    vertexColor = gl_Color;

    worldNormal = normalize(mat3(gbufferModelViewInverse) * (gl_NormalMatrix * gl_Normal));
#ifdef SEE_THROUGH
    // Relative to the camera, the space the battle camera hands its targets in.
    vec4 viewPos = gl_ModelViewMatrix * gl_Vertex;
    seeThroughPos = (gbufferModelViewInverse * viewPos).xyz;
#endif
}

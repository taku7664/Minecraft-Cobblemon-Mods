#version 330 compatibility
uniform mat4 gbufferModelViewInverse;

out vec3 cloudViewPosition;
out vec3 cloudWorldPosition;
out vec4 cloudVertexColor;

void main() {
    gl_Position = ftransform();
    cloudViewPosition = (gl_ModelViewMatrix * gl_Vertex).xyz;
    cloudWorldPosition = (gbufferModelViewInverse * vec4(cloudViewPosition, 1.0)).xyz;
    cloudVertexColor = gl_Color;
}

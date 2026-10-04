#version 330 compatibility
uniform mat4 gbufferModelViewInverse;
uniform float sunAngle;

in vec3 cloudViewPosition;
in vec3 cloudWorldPosition;
in vec4 cloudVertexColor;

/* DRAWBUFFERS:01 */

const float TAU = 6.28318530718;

float cloudCellNoise(vec2 cell) {
    vec3 p = fract(vec3(cell.xyx) * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

void main() {
    if (cloudVertexColor.a < 0.1) {
        discard;
    }

    vec3 viewNormal = normalize(cross(dFdx(cloudViewPosition), dFdy(cloudViewPosition)));
    if (!gl_FrontFacing) {
        viewNormal = -viewNormal;
    }
    vec3 worldNormal = normalize(mat3(gbufferModelViewInverse) * viewNormal);

    float dayAmount = smoothstep(-0.08, 0.08, sin(sunAngle * TAU));
    float topFace = smoothstep(0.25, 0.85, worldNormal.y);
    float lowerFace = smoothstep(0.15, 0.85, -worldNormal.y);
    float sideFace = 1.0 - max(topFace, lowerFace);

    vec3 nightCloud = vec3(0.34, 0.42, 0.62);
    vec3 dayCloud = vec3(0.94, 0.97, 1.00);
    vec3 cloudColor = mix(nightCloud, dayCloud, dayAmount);
    cloudColor *= 0.84 + topFace * 0.20 - lowerFace * 0.16 + sideFace * 0.02;

    vec2 cloudCell = floor(cloudWorldPosition.xz * 0.22);
    cloudColor *= mix(0.94, 1.04, cloudCellNoise(cloudCell));
    cloudColor *= mix(vec3(0.88, 0.94, 1.06), vec3(1.04, 1.01, 0.96), dayAmount);

    gl_FragData[0] = vec4(cloudColor, cloudVertexColor.a);
    // Blue channel marks clouds as already face-shaded. Composite must not
    // apply directional terrain lighting a second time.
    gl_FragData[1] = vec4(0.0, 1.0, 1.0, 1.0);
}

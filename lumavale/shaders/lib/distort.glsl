const bool shadowHardwareFiltering = true;
const bool shadowtex0Nearest = false;
const int shadowMapResolution = 2048; // [1024 2048 4096]
const float shadowDistance = 128.0;
const float shadowDistanceRenderMul = 1.0;

vec3 distortShadowClipPosition(vec3 position) {
    float distanceFromCenter = length(position.xy);
    float distortion = distanceFromCenter * 0.85 + 0.15;
    position.xy /= distortion;
    position.z *= 0.55;
    return position;
}

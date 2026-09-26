#version 330 compatibility

#include "/lib/settings.glsl"
#include "/lib/distort.glsl"
#include "/lib/color.glsl"

uniform sampler2D colortex0;
uniform sampler2D colortex1;
uniform sampler2D colortex2;
uniform sampler2D depthtex0;
uniform sampler2DShadow shadowtex0;

uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferModelViewInverse;
uniform mat4 shadowModelView;
uniform mat4 shadowProjection;

uniform vec3 shadowLightPosition;
uniform vec3 fogColor;
uniform float sunAngle;
uniform float rainStrength;
uniform float far;

in vec2 texcoord;

/*
const int colortex0Format = RGBA16F;
const int colortex1Format = RGBA8;
const int colortex2Format = RGBA8;
*/
const vec4 colortex1ClearColor = vec4(0.0, 0.0, 0.0, 0.0);
const vec4 colortex2ClearColor = vec4(0.5, 0.5, 1.0, 0.0);

/* DRAWBUFFERS:0 */

const float TAU = 6.28318530718;
#if SHADOW_SAMPLES == 4
const vec2 SHADOW_KERNEL[4] = vec2[4](
    vec2(0.3535533906, 0.0),
    vec2(-0.4515443759, 0.4136516368),
    vec2(0.0691161041, -0.7875423571),
    vec2(0.5691424396, 0.7423455283)
);
#elif SHADOW_SAMPLES == 8
const vec2 SHADOW_KERNEL[8] = vec2[8](
    vec2(0.25, 0.0),
    vec2(-0.3192900902, 0.2924958774),
    vec2(0.0488724659, -0.5568765411),
    vec2(0.4024444785, 0.5249175570),
    vec2(-0.7385351140, -0.1306364628),
    vec2(0.6996049319, -0.4450313913),
    vec2(-0.2340041582, 0.8704838045),
    vec2(-0.4462713077, -0.8592682468)
);
#else
#error SHADOW_SAMPLES must be 4 or 8
#endif

float hash12(vec2 value) {
    vec3 p = fract(vec3(value.xyx) * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

vec3 reconstructFeetPosition(float depth) {
    vec4 screenPosition = vec4(vec3(texcoord, depth) * 2.0 - 1.0, 1.0);
    vec4 viewPosition = gbufferProjectionInverse * screenPosition;
    viewPosition /= max(viewPosition.w, 0.00001);
    return (gbufferModelViewInverse * viewPosition).xyz;
}

float getAtmosphereFogFactor(float distanceFromCamera) {
    float atmosphereFog = smoothstep(far * 0.38, far * 0.94, distanceFromCamera) * FOG_STRENGTH;
    atmosphereFog *= mix(0.82, 1.18, rainStrength);
    return clamp(atmosphereFog, 0.0, 0.92);
}

float getBorderFogFactor(float distanceFromCamera) {
#ifdef BORDER_FOG
    float distanceRatio = distanceFromCamera / max(far, 1.0);
    float borderFog = smoothstep(BORDER_FOG_START, 0.98, distanceRatio);
    borderFog *= borderFog;
    return clamp(borderFog, 0.0, 1.0);
#else
    return 0.0;
#endif
}

float filteredShadow(vec3 feetPosition, float normalDotLight) {
    vec4 shadowViewPosition = shadowModelView * vec4(feetPosition, 1.0);
    vec4 baseClip = shadowProjection * shadowViewPosition;
    baseClip.z -= mix(0.00025, 0.00115, 1.0 - normalDotLight);

    float rotation = hash12(gl_FragCoord.xy) * TAU;
    vec2 rotationSinCos = vec2(sin(rotation), cos(rotation));
    float visibility = 0.0;
    for (int sampleIndex = 0; sampleIndex < SHADOW_SAMPLES; ++sampleIndex) {
        vec2 kernel = SHADOW_KERNEL[sampleIndex];
        vec2 offset = vec2(
            kernel.x * rotationSinCos.y - kernel.y * rotationSinCos.x,
            kernel.x * rotationSinCos.x + kernel.y * rotationSinCos.y
        );
        offset *= SHADOW_SOFTNESS / float(shadowMapResolution);

        vec4 sampleClip = baseClip;
        sampleClip.xy += offset;
        sampleClip.xyz = distortShadowClipPosition(sampleClip.xyz);
        vec3 samplePosition = (sampleClip.xyz / sampleClip.w) * 0.5 + 0.5;

        bool outside = any(lessThan(samplePosition.xy, vec2(0.001))) ||
                       any(greaterThan(samplePosition.xy, vec2(0.999))) ||
                       samplePosition.z <= 0.0 || samplePosition.z >= 1.0;
        if (outside) {
            visibility += 1.0;
        } else {
            visibility += texture(shadowtex0, vec3(samplePosition.xy, samplePosition.z - 0.00008));
        }
    }
    return visibility / float(SHADOW_SAMPLES);
}

vec3 daylightColor(float elevation) {
    vec3 lowSun = vec3(1.34, 0.72, 0.40);
    vec3 highSun = vec3(1.12, 1.03, 0.82);
    return mix(lowSun, highSun, smoothstep(0.05, 0.70, elevation));
}

void main() {
    vec4 base = texture(colortex0, texcoord);
    float depth = texture(depthtex0, texcoord).r;
    if (depth >= 0.999999) {
        gl_FragData[0] = vec4(toLinear(base.rgb) * EXPOSURE, base.a);
        return;
    }

    vec4 lightData = texture(colortex1, texcoord);
    vec2 lightmap = lightData.rg;
    bool isPrelitCloud = lightData.b > 0.5;
    vec3 feetPosition = reconstructFeetPosition(depth);

    float solarElevation = sin(sunAngle * TAU);
    float dayAmount = smoothstep(-0.08, 0.08, solarElevation);
    float distanceFromCamera = length(feetPosition);
    float atmosphereFogFactor = getAtmosphereFogFactor(distanceFromCamera);
    float borderFogFactor = getBorderFogFactor(distanceFromCamera);
    vec3 linearFog = toLinear(fogColor) * mix(0.72, 1.0, dayAmount);

    if (isPrelitCloud) {
        vec3 stableCloudFog = mix(
            toLinear(vec3(0.11, 0.17, 0.34)),
            toLinear(vec3(0.58, 0.75, 0.88)),
            dayAmount
        );
        vec3 shadedCloud = toLinear(base.rgb) * EXPOSURE;
        shadedCloud = mix(shadedCloud, stableCloudFog, atmosphereFogFactor);
        shadedCloud = mix(shadedCloud, linearFog, borderFogFactor);
        gl_FragData[0] = vec4(shadedCloud, base.a);
        return;
    }

    vec3 normal = normalize(texture(colortex2, texcoord).rgb * 2.0 - 1.0);
    float elevation = abs(solarElevation);
    float weatherSoftening = 1.0 - rainStrength * 0.48;

    vec3 worldLightDirection = normalize(mat3(gbufferModelViewInverse) * shadowLightPosition);
    float normalDotLight = max(dot(normal, worldLightDirection), 0.0);
    float wrappedDiffuse = max(normalDotLight * 0.82 + 0.18, 0.0);

    float skyLight = pow(clamp(lightmap.y, 0.0, 1.0), 1.35);
    float blockLight = pow(clamp(lightmap.x, 0.0, 1.0), 1.65);
    float shadow = 1.0;
    if (skyLight > 0.015 && wrappedDiffuse > 0.19) {
        shadow = filteredShadow(feetPosition, normalDotLight);
        shadow = mix(1.0, shadow, weatherSoftening);
    }

    vec3 dayAmbient = vec3(0.47, 0.58, 0.64);
    vec3 nightAmbient = vec3(0.070, 0.105, 0.235) * NIGHT_BRIGHTNESS;
    vec3 ambientColor = mix(nightAmbient, dayAmbient, dayAmount);

    vec3 sunColor = daylightColor(elevation);
    vec3 moonColor = vec3(0.19, 0.27, 0.50) * NIGHT_BRIGHTNESS;
    vec3 directColor = mix(moonColor, sunColor, dayAmount);
    directColor = mix(directColor, vec3(luminance(directColor)), rainStrength * 0.38);

    vec3 skyTint = mix(vec3(0.18, 0.30, 0.68), vec3(0.51, 0.66, 0.72), dayAmount);
    vec3 warmBlockLight = vec3(1.42, 0.62, 0.20) * blockLight * BLOCKLIGHT_STRENGTH;

    vec3 lighting = ambientColor * (0.22 + 0.78 * skyLight);
    lighting += skyTint * skyLight * 0.37;
    lighting += directColor * wrappedDiffuse * shadow * skyLight * 0.92;
    lighting += warmBlockLight;

    vec3 shaded = toLinear(base.rgb) * lighting * EXPOSURE;
    shaded = mix(shaded, linearFog, atmosphereFogFactor);
    shaded = mix(shaded, linearFog, borderFogFactor);

    gl_FragData[0] = vec4(shaded, base.a);
}

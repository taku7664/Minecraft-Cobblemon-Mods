// See-through for Better Cobblemon Battlecam: blocks standing between the battle camera and what it looks at
// (each Pokemon, each team, or a speaking trainer) thin out in a screen-door dither, so leaves and trunks do not
// hide the battle. The mod hands the values in through Iris; without it mcc_seeThrough stays 0 and nothing changes.
uniform float mcc_seeThrough;
uniform vec3 mcc_seeThroughA;
uniform vec3 mcc_seeThroughB;

in vec3 seeThroughPos;

// How much the fragment at p (camera-relative) is in the way of target, 0 to 1: inside a cone from the camera that
// widens toward the target, stopping short of the target itself so its ground stays.
float seeThroughOcclusion(vec3 p, vec3 target) {
    float reach = length(target);
    if (reach < 1.0) {
        return 0.0;
    }
    vec3 dir = target / reach;
    float along = dot(p, dir);
    if (along <= 0.2 || along >= reach - 0.8) {
        return 0.0;
    }
    float radius = mix(0.7, 1.8, along / reach);
    float off = length(p - dir * along);
    return 1.0 - smoothstep(radius * 0.6, radius, off);
}

float seeThroughBayer4(vec2 fragCoord) {
    ivec2 cell = ivec2(mod(fragCoord, 4.0));
    int index = cell.x + cell.y * 4;
    const float pattern[16] = float[16](0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0, 3.0, 11.0, 1.0, 9.0, 15.0, 7.0, 13.0, 5.0);
    return (pattern[index] + 0.5) / 16.0;
}

bool seeThroughDiscard() {
    if (mcc_seeThrough <= 0.0) {
        return false;
    }
    float occlusion = max(seeThroughOcclusion(seeThroughPos, mcc_seeThroughA), seeThroughOcclusion(seeThroughPos, mcc_seeThroughB));
    // At most three quarters clear, so what stands in the way still reads as there.
    return occlusion * mcc_seeThrough * 0.75 > seeThroughBayer4(gl_FragCoord.xy);
}

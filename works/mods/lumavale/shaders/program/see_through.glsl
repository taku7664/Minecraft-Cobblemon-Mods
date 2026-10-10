// See-through for Better Cobblemon Battlecam: blocks standing between the battle camera and what it looks at
// (each Pokemon, each team, or a speaking trainer) thin out in a screen-door dither, so leaves and trunks do not
// hide the battle. The mod hands the values in through Iris; without it mcc_seeThrough stays 0 and nothing changes.
uniform float mcc_seeThrough;
uniform vec3 mcc_seeThroughA;
uniform vec3 mcc_seeThroughB;

in vec3 seeThroughPos;

// How much the fragment at p (camera-relative) hides target (a body center), 0 to 1. Only fragments the target is
// actually behind count: the camera ray through p has to pass close to the target, and p has to sit in front of it.
// Nothing below the target's center counts, so the ground under and in front of a Pokemon stays put and the
// underground never opens up (the old fat cone around the camera-target line did both: haze and x-ray).
float seeThroughOcclusion(vec3 p, vec3 target) {
    if (dot(target, target) < 1.0 || p.y < target.y - 0.15) {
        return 0.0;
    }
    float depth = length(p);
    if (depth < 0.2) {
        return 0.0;
    }
    vec3 ray = p / depth;
    float along = dot(target, ray);
    if (depth >= along - 0.6) {
        return 0.0;
    }
    float miss = length(target - ray * along);
    return 1.0 - smoothstep(0.7, 1.2, miss);
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

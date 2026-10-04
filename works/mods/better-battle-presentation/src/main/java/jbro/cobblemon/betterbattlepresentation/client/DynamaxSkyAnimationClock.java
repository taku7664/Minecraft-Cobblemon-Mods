package jbro.cobblemon.betterbattlepresentation.client;

final class DynamaxSkyAnimationClock {
    static final String UNIFORM_NAME = "EffectTimeSeconds";
    private static final long LOOP_NANOS = 180_000_000_000L;

    private DynamaxSkyAnimationClock() {
    }

    static float seconds(long monotonicNanos) {
        return Math.floorMod(monotonicNanos, LOOP_NANOS) / 1_000_000_000.0F;
    }
}

package jbro.cobblemon.battleui.navigation;

/** Frame-time fade for battle modal backdrops; input ownership is unaffected. */
public final class VignetteFade {
    private static final long OPEN_NANOS = 200_000_000L;
    private static final long CLOSE_NANOS = 250_000_000L;
    private static final long IDLE_NANOS = 1_000_000_000L;

    private long previousNanos = Long.MIN_VALUE;
    private float opacity;

    public float advance(boolean active, long nowNanos) {
        if (previousNanos == Long.MIN_VALUE || nowNanos - previousNanos > IDLE_NANOS) {
            previousNanos = nowNanos;
            opacity = 0f;
            return opacity;
        }
        long elapsed = Math.max(0L, nowNanos - previousNanos);
        previousNanos = nowNanos;
        float step = (float) elapsed / (active ? OPEN_NANOS : CLOSE_NANOS);
        opacity = active ? Math.min(1f, opacity + step) : Math.max(0f, opacity - step);
        return opacity;
    }
}

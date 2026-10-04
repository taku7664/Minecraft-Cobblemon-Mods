package jbro.cobblemon.ui.dialogue;

/** Key-repeat timing for a held key: one step after a pause, then steady steps until release. */
public final class HoldRepeat {
    private final long delayNanos;
    private final long intervalNanos;
    private long nextNanos = -1L;

    public HoldRepeat(long delayNanos, long intervalNanos) {
        this.delayNanos = delayNanos;
        this.intervalNanos = intervalNanos;
    }

    public void press(long nowNanos) {
        nextNanos = nowNanos + delayNanos;
    }

    public void release() {
        nextNanos = -1L;
    }

    public boolean isHeld() {
        return nextNanos >= 0L;
    }

    /** Whether a repeat step is due now; at most one per call, so a slow frame does not skip several lines. */
    public boolean due(long nowNanos) {
        if (nextNanos < 0L || nowNanos < nextNanos) return false;
        nextNanos = nowNanos + intervalNanos;
        return true;
    }
}

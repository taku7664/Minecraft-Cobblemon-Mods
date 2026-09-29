package jbro.cobblemon.bettermusic.client;

final class LowHpAlertPulseScheduler {
    private final double cadenceSeconds;
    private double nextPulseSeconds = Double.NaN;
    private double lastUpdateSeconds = Double.NaN;

    LowHpAlertPulseScheduler(double cadenceSeconds) {
        if (!Double.isFinite(cadenceSeconds) || cadenceSeconds <= 0.0) {
            throw new IllegalArgumentException("cadenceSeconds must be positive and finite");
        }
        this.cadenceSeconds = cadenceSeconds;
    }

    boolean shouldPulse(double nowSeconds, boolean active) {
        if (!Double.isFinite(nowSeconds)) {
            throw new IllegalArgumentException("nowSeconds must be finite");
        }
        if (!active) {
            reset();
            return false;
        }
        if (Double.isNaN(nextPulseSeconds)
            || (!Double.isNaN(lastUpdateSeconds) && nowSeconds < lastUpdateSeconds)
            || nowSeconds >= nextPulseSeconds) {
            lastUpdateSeconds = nowSeconds;
            nextPulseSeconds = nowSeconds + cadenceSeconds;
            return true;
        }
        lastUpdateSeconds = nowSeconds;
        return false;
    }

    private void reset() {
        nextPulseSeconds = Double.NaN;
        lastUpdateSeconds = Double.NaN;
    }
}

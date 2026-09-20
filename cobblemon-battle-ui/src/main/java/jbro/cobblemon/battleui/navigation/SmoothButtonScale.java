package jbro.cobblemon.battleui.navigation;

public final class SmoothButtonScale {
    private final double restingScale;
    private final double focusedScale;
    private final double durationSeconds;

    public SmoothButtonScale(double restingScale, double focusedScale, double durationSeconds) {
        if (restingScale <= 0 || focusedScale <= 0 || durationSeconds <= 0) {
            throw new IllegalArgumentException("Scales and animation duration must be positive");
        }
        this.restingScale = restingScale;
        this.focusedScale = focusedScale;
        this.durationSeconds = durationSeconds;
    }

    public double advance(double currentScale, boolean focused, double deltaSeconds) {
        double target = focused ? focusedScale : restingScale;
        if (deltaSeconds <= 0 || currentScale == target) {
            return currentScale;
        }
        double fraction = Math.min(1.0, deltaSeconds / durationSeconds);
        double next = currentScale + (target - currentScale) * fraction;
        return target > currentScale ? Math.min(next, target) : Math.max(next, target);
    }
}

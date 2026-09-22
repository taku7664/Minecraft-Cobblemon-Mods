package jbro.cobblemon.battleui.extended;

/** Validation shared by config loading and runtime setters. */
public final class UiConfigSanitizer {
    private UiConfigSanitizer() {}

    public static float scale(float value, float minimum, float maximum, float fallback) {
        if (!Float.isFinite(value)) {
            return fallback;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static Integer optionalDimension(Integer value, int minimum, int maximum) {
        if (value == null) {
            return null;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }
}

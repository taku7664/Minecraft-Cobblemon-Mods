package jbro.cobblemon.bettermusic.config;

import java.util.Objects;

public record PlaybackSettings(
    double scanIntervalSeconds,
    double fieldChangeDelaySeconds,
    double betweenTracksSeconds,
    double fadeInSeconds,
    double fadeOutSeconds,
    MissingCueBehavior onMissingCue
) {
    public PlaybackSettings {
        requirePositiveFinite(scanIntervalSeconds, "scanIntervalSeconds");
        requireNonNegativeFinite(fieldChangeDelaySeconds, "fieldChangeDelaySeconds");
        requireNonNegativeFinite(betweenTracksSeconds, "betweenTracksSeconds");
        requireNonNegativeFinite(fadeInSeconds, "fadeInSeconds");
        requireNonNegativeFinite(fadeOutSeconds, "fadeOutSeconds");
        Objects.requireNonNull(onMissingCue, "onMissingCue");
    }

    private static void requirePositiveFinite(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and greater than zero");
        }
    }

    private static void requireNonNegativeFinite(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    public enum MissingCueBehavior {
        FALLBACK,
        KEEP_ORIGINAL,
        SILENCE
    }
}

package jbro.cobblemon.bettermusic.config;

public record PlaybackSettings(
    double scanIntervalSeconds,
    double fieldChangeDelaySeconds,
    double betweenTracksSeconds,
    double fadeInSeconds,
    double fadeOutSeconds
) {
    public PlaybackSettings {
        requirePositiveFinite(scanIntervalSeconds, "scanIntervalSeconds");
        requireNonNegativeFinite(fieldChangeDelaySeconds, "fieldChangeDelaySeconds");
        requireNonNegativeFinite(betweenTracksSeconds, "betweenTracksSeconds");
        requireNonNegativeFinite(fadeInSeconds, "fadeInSeconds");
        requireNonNegativeFinite(fadeOutSeconds, "fadeOutSeconds");
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
}

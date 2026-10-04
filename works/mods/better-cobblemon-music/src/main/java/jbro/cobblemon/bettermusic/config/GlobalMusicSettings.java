package jbro.cobblemon.bettermusic.config;

import java.util.Objects;

public record GlobalMusicSettings(
    double scanIntervalSeconds,
    double fieldChangeDelaySeconds,
    double betweenTracksSeconds,
    double fadeInSeconds,
    double fadeOutSeconds,
    PlaylistDefinition.Selection selection,
    double volume,
    AudioEffectsSettings audioEffects
) {
    public GlobalMusicSettings {
        new PlaybackSettings(
            scanIntervalSeconds,
            fieldChangeDelaySeconds,
            betweenTracksSeconds,
            fadeInSeconds,
            fadeOutSeconds
        );
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(audioEffects, "audioEffects");
        if (!Double.isFinite(volume) || volume < 0.0 || volume > Float.MAX_VALUE) {
            throw new IllegalArgumentException("volume must be a non-negative finite float");
        }
    }
}

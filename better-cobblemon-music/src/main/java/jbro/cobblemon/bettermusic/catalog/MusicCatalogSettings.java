package jbro.cobblemon.bettermusic.catalog;

import java.util.Objects;
import jbro.cobblemon.bettermusic.config.AudioEffectsSettings;
import jbro.cobblemon.bettermusic.config.PlaybackSettings;
import jbro.cobblemon.bettermusic.config.PlaylistDefinition;

public record MusicCatalogSettings(
    String basePackId,
    PlaybackSettings playback,
    PlaylistDefinition.Selection selection,
    double volume,
    AudioEffectsSettings audioEffects
) {
    public MusicCatalogSettings {
        Objects.requireNonNull(basePackId, "basePackId");
        Objects.requireNonNull(playback, "playback");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(audioEffects, "audioEffects");
        if (!Double.isFinite(volume) || volume < 0.0 || volume > Float.MAX_VALUE) {
            throw new IllegalArgumentException("volume must be a non-negative finite float");
        }
    }

    public static MusicCatalogSettings defaults(String basePackId) {
        return new MusicCatalogSettings(
            basePackId,
            new PlaybackSettings(1.0, 4.0, 0.0, 1.0, 1.0),
            PlaylistDefinition.Selection.SHUFFLE,
            1.0,
            AudioEffectsSettings.defaults()
        );
    }
}

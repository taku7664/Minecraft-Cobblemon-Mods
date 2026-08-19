package jbro.cobblemon.bettermusic.config;

import java.util.Objects;

public record MusicConfig(
    PlaybackSettings playback,
    FieldMusicConfig field,
    BattleMusicConfig battle
) {
    public MusicConfig {
        Objects.requireNonNull(playback, "playback");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(battle, "battle");
    }
}

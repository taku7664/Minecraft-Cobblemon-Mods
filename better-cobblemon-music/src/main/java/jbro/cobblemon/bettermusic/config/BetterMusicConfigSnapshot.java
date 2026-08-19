package jbro.cobblemon.bettermusic.config;

import java.util.Objects;
public record BetterMusicConfigSnapshot(
    PlaybackSettings playback,
    FieldMusicConfig field,
    BattleMusicConfig battle
) {
    public BetterMusicConfigSnapshot {
        Objects.requireNonNull(playback, "playback");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(battle, "battle");
    }
}

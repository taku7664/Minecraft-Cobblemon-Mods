package jbro.cobblemon.bettermusic.config;

import java.util.Objects;

public record BetterMusicConfigSnapshot(
    PlaybackSettings playback,
    AudioEffectsSettings audioEffects,
    FieldMusicConfig field,
    BattleMusicConfig battle
) {
    public BetterMusicConfigSnapshot {
        Objects.requireNonNull(playback, "playback");
        Objects.requireNonNull(audioEffects, "audioEffects");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(battle, "battle");
    }

    public BetterMusicConfigSnapshot(
        PlaybackSettings playback,
        FieldMusicConfig field,
        BattleMusicConfig battle
    ) {
        this(playback, AudioEffectsSettings.defaults(), field, battle);
    }
}

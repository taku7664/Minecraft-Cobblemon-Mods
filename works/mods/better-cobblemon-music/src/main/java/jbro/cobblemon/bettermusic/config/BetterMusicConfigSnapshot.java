package jbro.cobblemon.bettermusic.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** The music configuration in effect; {@code screens} holds the playlists of screens such as a content hub. */
public record BetterMusicConfigSnapshot(
    PlaybackSettings playback,
    AudioEffectsSettings audioEffects,
    FieldMusicConfig field,
    BattleMusicConfig battle,
    Map<String, PlaylistDefinition> screens
) {
    public BetterMusicConfigSnapshot {
        Objects.requireNonNull(playback, "playback");
        Objects.requireNonNull(audioEffects, "audioEffects");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(battle, "battle");
        screens = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(screens, "screens")));
    }

    public BetterMusicConfigSnapshot(
        PlaybackSettings playback,
        AudioEffectsSettings audioEffects,
        FieldMusicConfig field,
        BattleMusicConfig battle
    ) {
        this(playback, audioEffects, field, battle, Map.of());
    }

    public BetterMusicConfigSnapshot(
        PlaybackSettings playback,
        FieldMusicConfig field,
        BattleMusicConfig battle
    ) {
        this(playback, AudioEffectsSettings.defaults(), field, battle);
    }
}

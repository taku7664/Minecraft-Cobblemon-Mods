package jbro.cobblemon.bettermusic.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class MusicFileSoundIdsTest {
    @Test
    void relativeOggPathMapsToGeneratedResourcePackSoundEvent() {
        assertEquals(
            "better_cobblemon_music:custom/battle/pvp/theme_1",
            MusicFileSoundIds.soundEvent("battle/pvp/theme_1.ogg")
        );
    }
}

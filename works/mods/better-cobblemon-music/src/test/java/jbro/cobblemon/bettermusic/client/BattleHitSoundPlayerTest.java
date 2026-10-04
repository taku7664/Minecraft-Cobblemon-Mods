package jbro.cobblemon.bettermusic.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jbro.cobblemon.bettermusic.audio.HitEffectiveness;
import jbro.cobblemon.bettermusic.catalog.MusicCatalog;
import org.junit.jupiter.api.Test;

final class BattleHitSoundPlayerTest {
    @Test
    void resolvesHitEventsFromTheActiveBaseCatalog() {
        var events = new MusicCatalog.AudioEvents("pack:normal", "pack:super", "pack:weak", "pack:heart");

        assertEquals("pack:normal", BattleHitSoundPlayer.event(HitEffectiveness.NORMAL, events));
        assertEquals("pack:super", BattleHitSoundPlayer.event(HitEffectiveness.SUPER_EFFECTIVE, events));
        assertEquals("pack:weak", BattleHitSoundPlayer.event(HitEffectiveness.NOT_VERY_EFFECTIVE, events));
    }
}

package jbro.cobblemon.bettermusic.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class BattleHitSoundIdsTest {
    @Test
    void preservesTheExistingCobbleServerResourcePackIds() {
        assertEquals("cobleserver:battle.hit.normal", BattleHitSoundIds.event(HitEffectiveness.NORMAL));
        assertEquals(
            "cobleserver:battle.hit.super_effective",
            BattleHitSoundIds.event(HitEffectiveness.SUPER_EFFECTIVE)
        );
        assertEquals(
            "cobleserver:battle.hit.not_very_effective",
            BattleHitSoundIds.event(HitEffectiveness.NOT_VERY_EFFECTIVE)
        );
    }
}

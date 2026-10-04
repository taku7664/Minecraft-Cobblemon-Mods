package jbro.cobblemon.bettermusic.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class ClientHitSoundTrackerTest {
    private ClientHitSoundTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ClientHitSoundTracker();
    }

    @Test
    void classifiesSuperEffectiveDamageWithinTheCurrentMove() {
        tracker.onBattleMessageKey("cobblemon.battle.used_move");
        tracker.onBattleMessageKey("cobblemon.battle.superEffective");

        assertEquals(
            HitEffectiveness.SUPER_EFFECTIVE,
            tracker.onHealthChange("p2a", 100.0F, 40.0F).orElseThrow()
        );
    }

    @Test
    void classifiesResistedDamageWithinTheCurrentMove() {
        tracker.onBattleMessageKey("cobblemon.battle.used_move_on");
        tracker.onBattleMessageKey("cobblemon.battle.mostlyIneffective");

        assertEquals(
            HitEffectiveness.NOT_VERY_EFFECTIVE,
            tracker.onHealthChange("p2a", 100.0F, 80.0F).orElseThrow()
        );
    }

    @Test
    void defaultsToNormalWhenDamageHasNoEffectivenessMessage() {
        tracker.onBattleMessageKey("cobblemon.battle.used_move");

        assertEquals(
            HitEffectiveness.NORMAL,
            tracker.onHealthChange("p2a", 100.0F, 70.0F).orElseThrow()
        );
    }

    @Test
    void doesNotPlayForHealingOrDamageOutsideAMoveWindow() {
        assertTrue(tracker.onHealthChange("p2a", 100.0F, 70.0F).isEmpty());

        tracker.onBattleMessageKey("cobblemon.battle.used_move");
        assertTrue(tracker.onHealthChange("p2a", 70.0F, 100.0F).isEmpty());
    }

    @Test
    void terminalBattleMessagesDiscardPendingEffectiveness() {
        tracker.onBattleMessageKey("cobblemon.battle.used_move");
        tracker.onBattleMessageKey("cobblemon.battle.superEffective_spread");
        tracker.onBattleMessageKey("cobblemon.battle.missed");

        assertTrue(tracker.onHealthChange("p2a", 100.0F, 50.0F).isEmpty());
    }

    @Test
    void clearDropsMoveAndHealthState() {
        tracker.onBattleMessageKey("cobblemon.battle.used_move");
        tracker.onBattleMessageKey("cobblemon.battle.resisted");
        tracker.clear();

        assertTrue(tracker.onHealthChange("p2a", 100.0F, 50.0F).isEmpty());
    }
}

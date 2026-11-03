package jbro.cobblemon.battlespeed.core;

import jbro.cobblemon.battlespeed.config.BattleSpeedConfig;
import jbro.cobblemon.battlespeed.core.BattleSpeedPolicy.Participant;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BattleSpeedPolicyTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static BattleSpeedConfig defaults() {
        return new BattleSpeedConfig().normalized();
    }

    private static ToIntFunction<UUID> levels(Map<UUID, Integer> levels) {
        return id -> levels.getOrDefault(id, 1);
    }

    @Test
    void wildBattleRunsAtThePlayersLevel() {
        var sides = List.of(List.of(Participant.player(ALICE)), List.of(Participant.wild()));
        assertEquals(4.0, BattleSpeedPolicy.levelSpeed(defaults(), sides, levels(Map.of(ALICE, 4))));
        assertEquals(2.0, BattleSpeedPolicy.levelSpeed(defaults(), sides, levels(Map.of(ALICE, 2))));
    }

    @Test
    void npcBattleRunsAtThePlayersLevel() {
        var sides = List.of(List.of(Participant.player(ALICE)), List.of(Participant.npc()));
        assertEquals(3.0, BattleSpeedPolicy.levelSpeed(defaults(), sides, levels(Map.of(ALICE, 3))));
    }

    @Test
    void pvpStaysAtNormalSpeedByDefault() {
        var sides = List.of(List.of(Participant.player(ALICE)), List.of(Participant.player(BOB)));
        assertFalse(BattleSpeedPolicy.eligible(defaults(), sides));
        assertEquals(1.0, BattleSpeedPolicy.levelSpeed(defaults(), sides, levels(Map.of(ALICE, 4, BOB, 4))));
    }

    @Test
    void pvpWhenTurnedOnTakesTheSlowestPick() {
        var config = defaults();
        config.battles.pvp = true;
        var sides = List.of(List.of(Participant.player(ALICE)), List.of(Participant.player(BOB)));
        assertEquals(2.0, BattleSpeedPolicy.levelSpeed(config, sides, levels(Map.of(ALICE, 4, BOB, 2))));
    }

    @Test
    void pvpStaysSlowestEvenWhenMultiplePlayersIsNormal() {
        var config = defaults();
        config.battles.pvp = true;
        config.multiplePlayers = BattleSpeedConfig.NORMAL;
        var sides = List.of(List.of(Participant.player(ALICE)), List.of(Participant.player(BOB)));
        assertEquals(3.0, BattleSpeedPolicy.levelSpeed(config, sides, levels(Map.of(ALICE, 4, BOB, 3))));
    }

    @Test
    void coopPlayersFollowTheMultiplePlayersRule() {
        var sides = List.of(
            List.of(Participant.player(ALICE), Participant.player(BOB)),
            List.of(Participant.npc(), Participant.npc())
        );
        var choices = levels(Map.of(ALICE, 4, BOB, 2));
        assertEquals(2.0, BattleSpeedPolicy.levelSpeed(defaults(), sides, choices));

        var normal = defaults();
        normal.multiplePlayers = BattleSpeedConfig.NORMAL;
        assertEquals(1.0, BattleSpeedPolicy.levelSpeed(normal, sides, choices));
    }

    @Test
    void turnedOffBattleKindsStayAtNormalSpeed() {
        var config = defaults();
        config.battles.wild = false;
        var wild = List.of(List.of(Participant.player(ALICE)), List.of(Participant.wild()));
        var npc = List.of(List.of(Participant.player(ALICE)), List.of(Participant.npc()));
        assertEquals(1.0, BattleSpeedPolicy.levelSpeed(config, wild, levels(Map.of(ALICE, 4))));
        assertEquals(4.0, BattleSpeedPolicy.levelSpeed(config, npc, levels(Map.of(ALICE, 4))));
    }

    @Test
    void battlesWithoutPlayersAndDisabledModStayAtNormalSpeed() {
        var npcOnly = List.of(List.of(Participant.npc()), List.of(Participant.wild()));
        assertFalse(BattleSpeedPolicy.eligible(defaults(), npcOnly));

        var off = defaults();
        off.enabled = false;
        var wild = List.of(List.of(Participant.player(ALICE)), List.of(Participant.wild()));
        assertEquals(1.0, BattleSpeedPolicy.levelSpeed(off, wild, levels(Map.of(ALICE, 4))));
    }

    @Test
    void storedLevelAboveTheListIsClamped() {
        var config = defaults();
        config.speeds = List.of(1.0, 2.0);
        config.normalized();
        var sides = List.of(List.of(Participant.player(ALICE)), List.of(Participant.wild()));
        assertEquals(2.0, BattleSpeedPolicy.levelSpeed(config, sides, levels(Map.of(ALICE, 4))));
    }

    @Test
    void moveEffectsAreSkippedOnlyFromTheConfiguredLevel() {
        var config = defaults();
        assertFalse(BattleSpeedPolicy.skipMoveEffects(config, 4.0));
        config.skipMoveEffectsFromLevel = 4;
        assertTrue(BattleSpeedPolicy.skipMoveEffects(config, 4.0));
        assertFalse(BattleSpeedPolicy.skipMoveEffects(config, 3.0));
        config.skipMoveEffectsFromLevel = 1;
        assertFalse(BattleSpeedPolicy.skipMoveEffects(config, 1.0));
    }

    @Test
    void speedsReadNaturallyInChat() {
        assertEquals("2", BattleSpeedPolicy.formatSpeed(2.0));
        assertEquals("1.5", BattleSpeedPolicy.formatSpeed(1.5));
        assertEquals("1.25", BattleSpeedPolicy.formatSpeed(1.25));
    }
}

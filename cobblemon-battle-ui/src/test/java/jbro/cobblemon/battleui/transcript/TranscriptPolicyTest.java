package jbro.cobblemon.battleui.transcript;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TranscriptPolicyTest {
    private TranscriptPolicy.Event<String, String> event(int turn, String key, String speaker) {
        return new TranscriptPolicy.Event<>(turn, key, speaker, key);
    }

    @Test void moveAndResultsStayWithAttackerNotDamagedTarget() {
        var groups = TranscriptPolicy.group(List.of(
                event(1, "cobblemon.battle.used_move", "self"),
                event(1, "cobblemon.battle.superEffective", "enemy"),
                event(1, "cobblemon.battle.crit", null),
                event(1, "cobblemon.battle.used_move", "enemy")));
        assertEquals(2, groups.size());
        assertEquals("self", groups.getFirst().speaker());
        assertEquals(3, groups.getFirst().values().size());
        assertEquals("enemy", groups.getLast().speaker());
    }

    @Test void residualDamageAndUnknownTextBreakActionGroups() {
        var groups = TranscriptPolicy.group(List.of(
                event(1, "cobblemon.battle.used_move", "self"),
                event(1, "cobblemon.status.burn.hurt", "enemy"),
                event(1, "unknown.mod.message", null),
                event(1, "cobblemon.battle.crit", null)));
        assertEquals(4, groups.size());
        assertNull(groups.get(1).speaker());
        assertNull(groups.get(3).speaker());
    }

    @Test void turnsAndFieldMessagesNeverInheritAnActor() {
        var groups = TranscriptPolicy.group(List.of(
                event(1, "cobblemon.battle.used_move", "self"),
                event(2, "cobblemon.battle.turn", null),
                event(2, "cobblemon.battle.crit", null),
                event(2, "cobblemon.battle.weather.rain", "enemy")));
        assertEquals(4, groups.size());
        assertEquals(TranscriptPolicy.Kind.TURN, groups.get(1).kind());
        assertNull(groups.getLast().speaker());
        assertNull(groups.get(2).speaker());
    }

    @Test void ambiguousMoveCannotLendAnInventedSpeaker() {
        var groups = TranscriptPolicy.group(List.of(
                event(1, "cobblemon.battle.used_move", null),
                event(1, "cobblemon.battle.crit", null)));
        assertEquals(2, groups.size());
        assertTrue(groups.stream().allMatch(g -> g.speaker() == null));
    }

    @Test void sameActorConsecutiveMovesRemainSeparateAndOrderIsPreserved() {
        var groups = TranscriptPolicy.group(List.of(
                event(1, "cobblemon.battle.used_move", "self"),
                event(1, "cobblemon.battle.used_move", "self"),
                event(1, "cobblemon.status.burn.apply", "enemy")));
        assertEquals(2, groups.size());
        assertEquals(2, groups.getLast().values().size());
    }
}

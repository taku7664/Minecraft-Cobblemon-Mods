package jbro.cobblemon.betterbattlepresentation.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class DynamaxBattleStateTrackerTest {
    private final UUID battleId = UUID.randomUUID();
    private final UUID firstPokemon = UUID.randomUUID();
    private final UUID secondPokemon = UUID.randomUUID();

    @Test
    void activatesOnceAndWaitsForTheLastDynamaxedPokemonBeforeDeactivating() {
        var tracker = new DynamaxBattleStateTracker();

        assertEquals(DynamaxBattleStateTracker.Transition.ACTIVATED, tracker.start(battleId, firstPokemon));
        assertEquals(DynamaxBattleStateTracker.Transition.UNCHANGED, tracker.start(battleId, firstPokemon));
        assertEquals(DynamaxBattleStateTracker.Transition.UNCHANGED, tracker.start(battleId, secondPokemon));
        assertTrue(tracker.isActive(battleId));

        assertEquals(DynamaxBattleStateTracker.Transition.UNCHANGED, tracker.end(battleId, firstPokemon));
        assertTrue(tracker.isActive(battleId));
        assertEquals(DynamaxBattleStateTracker.Transition.DEACTIVATED, tracker.end(battleId, secondPokemon));
        assertFalse(tracker.isActive(battleId));
    }

    @Test
    void duplicateAndUnknownEndEventsAreIdempotent() {
        var tracker = new DynamaxBattleStateTracker();

        assertEquals(DynamaxBattleStateTracker.Transition.UNCHANGED, tracker.end(battleId, firstPokemon));
        tracker.start(battleId, firstPokemon);
        assertEquals(DynamaxBattleStateTracker.Transition.DEACTIVATED, tracker.forceEnd(battleId));
        assertEquals(DynamaxBattleStateTracker.Transition.UNCHANGED, tracker.forceEnd(battleId));
    }
}

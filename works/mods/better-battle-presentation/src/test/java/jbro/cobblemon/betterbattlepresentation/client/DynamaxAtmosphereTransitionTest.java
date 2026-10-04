package jbro.cobblemon.betterbattlepresentation.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class DynamaxAtmosphereTransitionTest {
    private static final long DURATION = 800_000_000L;

    @Test
    void fadesInAndOutWithFrameIndependentSmoothstepTiming() {
        var state = new DynamaxAtmosphereTransition(DURATION);
        var battleId = UUID.randomUUID();

        state.setActive(battleId, true, 1_000L);
        assertEquals(0.0F, state.strength(1_000L), 0.0001F);
        assertEquals(0.5F, state.strength(400_001_000L), 0.0001F);
        assertEquals(1.0F, state.strength(800_001_000L), 0.0001F);

        state.setActive(battleId, false, 800_001_000L);
        assertEquals(0.5F, state.strength(1_200_001_000L), 0.0001F);
        assertEquals(0.0F, state.strength(1_600_001_000L), 0.0001F);
    }

    @Test
    void removingOneBattleDoesNotFadeWhileAnotherBattleIsActive() {
        var state = new DynamaxAtmosphereTransition(DURATION);
        var firstBattle = UUID.randomUUID();
        var secondBattle = UUID.randomUUID();

        state.setActive(firstBattle, true, 0L);
        state.setActive(secondBattle, true, DURATION);
        state.setActive(firstBattle, false, DURATION);

        assertTrue(state.isActive());
        assertEquals(1.0F, state.strength(DURATION * 2), 0.0001F);
    }

    @Test
    void reversingMidFadeContinuesFromTheCurrentStrengthWithoutJumping() {
        var state = new DynamaxAtmosphereTransition(DURATION);
        var battleId = UUID.randomUUID();

        state.setActive(battleId, true, 0L);
        float beforeReverse = state.strength(DURATION / 2);
        state.setActive(battleId, false, DURATION / 2);

        assertEquals(beforeReverse, state.strength(DURATION / 2), 0.0001F);
        assertEquals(0.0F, state.strength(DURATION + DURATION / 2), 0.0001F);
        assertFalse(state.isActive());
    }

    @Test
    void clearImmediatelyDropsDisconnectedWorldState() {
        var state = new DynamaxAtmosphereTransition(DURATION);
        state.setActive(UUID.randomUUID(), true, 0L);
        state.clear();

        assertFalse(state.isActive());
        assertEquals(0.0F, state.strength(DURATION), 0.0001F);
    }
}

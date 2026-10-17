package jbro.cobblemon.ui.extended.state;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LastMoveMemoryTest {
    private final UUID pikachu = UUID.randomUUID();
    private final UUID eevee = UUID.randomUUID();

    @BeforeEach
    void reset() {
        LastMoveMemory.clear();
    }

    @Test
    void opensOnTheMoveChosenLastTurnByItsId() {
        LastMoveMemory.record(pikachu, "thunderbolt");
        assertEquals(1, LastMoveMemory.indexFor(pikachu, List.of("quickattack", "thunderbolt"), i -> true));
        // Another order (a gimmick's own tiles): still the same move.
        assertEquals(0, LastMoveMemory.indexFor(pikachu, List.of("thunderbolt", "quickattack"), i -> true));
    }

    @Test
    void eachPokemonKeepsItsOwn() {
        LastMoveMemory.record(pikachu, "thunderbolt");
        LastMoveMemory.record(eevee, "bite");
        assertEquals(1, LastMoveMemory.indexFor(eevee, List.of("thunderbolt", "bite"), i -> true));
        assertEquals(0, LastMoveMemory.indexFor(pikachu, List.of("thunderbolt", "bite"), i -> true));
    }

    @Test
    void noneWhenNothingRememberedGoneOrDisabled() {
        assertEquals(-1, LastMoveMemory.indexFor(pikachu, List.of("thunderbolt"), i -> true));
        LastMoveMemory.record(pikachu, "thunderbolt");
        assertEquals(-1, LastMoveMemory.indexFor(pikachu, List.of("quickattack"), i -> true));
        assertEquals(-1, LastMoveMemory.indexFor(pikachu, List.of("thunderbolt"), i -> false));
        assertEquals(-1, LastMoveMemory.indexFor(null, List.of("thunderbolt"), i -> true));
    }

    @Test
    void theLatestChoiceWins() {
        LastMoveMemory.record(pikachu, "thunderbolt");
        LastMoveMemory.record(pikachu, "quickattack");
        assertEquals(0, LastMoveMemory.indexFor(pikachu, List.of("quickattack", "thunderbolt"), i -> true));
    }

    @Test
    void dropsTheOldestPastCapacity() {
        LastMoveMemory.record(pikachu, "thunderbolt");
        for (int i = 0; i < LastMoveMemory.CAPACITY; i++) {
            LastMoveMemory.record(UUID.randomUUID(), "tackle");
        }
        assertEquals(-1, LastMoveMemory.indexFor(pikachu, List.of("thunderbolt"), i -> true));
    }
}

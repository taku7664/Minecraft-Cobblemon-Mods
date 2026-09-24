package jbro.cobblemon.bettermusic.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class LastPokemonMuffleTrackerTest {
    private static final UUID BATTLE_ONE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BATTLE_TWO = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID FIRST = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID THIRD = UUID.fromString("10000000-0000-0000-0000-000000000003");

    @Test
    void stagesEffectsAtHalfAndCobblemonRedHealthForMyLastUsablePokemon() {
        var tracker = new LastPokemonMuffleTracker();
        var team = team(false, false, false);

        assertEffect(tracker, team, 0.2, LastPokemonMuffleTracker.Effect.NONE);
        tracker.markFainted(BATTLE_ONE, FIRST);
        assertEffect(tracker, team, 0.2, LastPokemonMuffleTracker.Effect.NONE);
        tracker.markFainted(BATTLE_ONE, SECOND);
        assertEffect(tracker, team, 0.5001, LastPokemonMuffleTracker.Effect.NONE);
        assertEffect(tracker, team, 0.5, LastPokemonMuffleTracker.Effect.MUFFLED);
        assertEffect(tracker, team, 0.2001, LastPokemonMuffleTracker.Effect.MUFFLED);
        assertEffect(tracker, team, 0.2, LastPokemonMuffleTracker.Effect.CRITICAL);
        assertEffect(tracker, team, 0.01, LastPokemonMuffleTracker.Effect.CRITICAL);
        assertEffect(tracker, team, 0.0, LastPokemonMuffleTracker.Effect.NONE);
        tracker.markFainted(BATTLE_ONE, THIRD);
        assertEffect(tracker, team, 0.2, LastPokemonMuffleTracker.Effect.NONE);
    }

    @Test
    void respectsFaintedStateAlreadyPresentInCobblemonTeamData() {
        var tracker = new LastPokemonMuffleTracker();

        assertEquals(
            LastPokemonMuffleTracker.Effect.CRITICAL,
            tracker.update(BATTLE_ONE, team(true, true, false), health(THIRD, 0.2))
        );
        assertEquals(
            LastPokemonMuffleTracker.Effect.NONE,
            tracker.update(BATTLE_ONE, team(true, true, true), health(THIRD, 0.2))
        );
    }

    @Test
    void doesNotMuffleWhileTheLastPokemonIsNotActive() {
        var tracker = new LastPokemonMuffleTracker();

        assertEquals(
            LastPokemonMuffleTracker.Effect.NONE,
            tracker.update(BATTLE_ONE, team(true, true, false), Map.of())
        );
    }

    @Test
    void changingBattlesDropsFaintsFromThePreviousBattle() {
        var tracker = new LastPokemonMuffleTracker();
        var team = team(false, false, false);
        tracker.markFainted(BATTLE_ONE, FIRST);
        tracker.markFainted(BATTLE_ONE, SECOND);
        assertEffect(tracker, team, 0.2, LastPokemonMuffleTracker.Effect.CRITICAL);

        assertEquals(
            LastPokemonMuffleTracker.Effect.NONE,
            tracker.update(BATTLE_TWO, team, health(THIRD, 0.2))
        );
    }

    @Test
    void authoritativeTeamUpdateCanRestoreARevivedPokemon() {
        var tracker = new LastPokemonMuffleTracker();
        var team = team(false, false, false);
        tracker.markFainted(BATTLE_ONE, FIRST);
        tracker.markFainted(BATTLE_ONE, SECOND);
        assertEffect(tracker, team, 0.2, LastPokemonMuffleTracker.Effect.CRITICAL);

        tracker.updatePokemon(BATTLE_ONE, new LastPokemonMuffleTracker.TeamPokemon(SECOND, false));

        assertEffect(tracker, team, 0.2, LastPokemonMuffleTracker.Effect.NONE);
    }

    private static List<LastPokemonMuffleTracker.TeamPokemon> team(
        boolean firstFainted,
        boolean secondFainted,
        boolean thirdFainted
    ) {
        return List.of(
            new LastPokemonMuffleTracker.TeamPokemon(FIRST, firstFainted),
            new LastPokemonMuffleTracker.TeamPokemon(SECOND, secondFainted),
            new LastPokemonMuffleTracker.TeamPokemon(THIRD, thirdFainted)
        );
    }

    private static Map<UUID, Double> health(UUID pokemon, double ratio) {
        return Map.of(pokemon, ratio);
    }

    private static void assertEffect(
        LastPokemonMuffleTracker tracker,
        List<LastPokemonMuffleTracker.TeamPokemon> team,
        double healthRatio,
        LastPokemonMuffleTracker.Effect expected
    ) {
        assertEquals(expected, tracker.update(BATTLE_ONE, team, health(THIRD, healthRatio)));
    }
}

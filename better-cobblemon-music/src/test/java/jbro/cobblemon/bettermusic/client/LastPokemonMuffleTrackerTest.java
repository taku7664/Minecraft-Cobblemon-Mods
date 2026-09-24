package jbro.cobblemon.bettermusic.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void mufflesOnlyWhenMyLastUsablePokemonIsAtCobblemonRedHealth() {
        var tracker = new LastPokemonMuffleTracker();
        var team = team(false, false, false);

        assertFalse(tracker.update(BATTLE_ONE, team, health(THIRD, 0.2)));
        tracker.markFainted(BATTLE_ONE, FIRST);
        assertFalse(tracker.update(BATTLE_ONE, team, health(THIRD, 0.2)));
        tracker.markFainted(BATTLE_ONE, SECOND);
        assertFalse(tracker.update(BATTLE_ONE, team, health(THIRD, 0.2001)));
        assertTrue(tracker.update(BATTLE_ONE, team, health(THIRD, 0.2)));
        assertTrue(tracker.update(BATTLE_ONE, team, health(THIRD, 0.01)));
        assertFalse(tracker.update(BATTLE_ONE, team, health(THIRD, 0.0)));
        tracker.markFainted(BATTLE_ONE, THIRD);
        assertFalse(tracker.update(BATTLE_ONE, team, health(THIRD, 0.2)));
    }

    @Test
    void respectsFaintedStateAlreadyPresentInCobblemonTeamData() {
        var tracker = new LastPokemonMuffleTracker();

        assertTrue(tracker.update(BATTLE_ONE, team(true, true, false), health(THIRD, 0.2)));
        assertFalse(tracker.update(BATTLE_ONE, team(true, true, true), health(THIRD, 0.2)));
    }

    @Test
    void doesNotMuffleWhileTheLastPokemonIsNotActive() {
        var tracker = new LastPokemonMuffleTracker();

        assertFalse(tracker.update(BATTLE_ONE, team(true, true, false), Map.of()));
    }

    @Test
    void changingBattlesDropsFaintsFromThePreviousBattle() {
        var tracker = new LastPokemonMuffleTracker();
        var team = team(false, false, false);
        tracker.markFainted(BATTLE_ONE, FIRST);
        tracker.markFainted(BATTLE_ONE, SECOND);
        assertTrue(tracker.update(BATTLE_ONE, team, health(THIRD, 0.2)));

        assertFalse(tracker.update(BATTLE_TWO, team, health(THIRD, 0.2)));
    }

    @Test
    void authoritativeTeamUpdateCanRestoreARevivedPokemon() {
        var tracker = new LastPokemonMuffleTracker();
        var team = team(false, false, false);
        tracker.markFainted(BATTLE_ONE, FIRST);
        tracker.markFainted(BATTLE_ONE, SECOND);
        assertTrue(tracker.update(BATTLE_ONE, team, health(THIRD, 0.2)));

        tracker.updatePokemon(BATTLE_ONE, new LastPokemonMuffleTracker.TeamPokemon(SECOND, false));

        assertFalse(tracker.update(BATTLE_ONE, team, health(THIRD, 0.2)));
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
}

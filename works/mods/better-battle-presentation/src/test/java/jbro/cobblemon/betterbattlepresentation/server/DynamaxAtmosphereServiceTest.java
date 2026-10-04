package jbro.cobblemon.betterbattlepresentation.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class DynamaxAtmosphereServiceTest {
    @Test
    void routesActivationAndFinalDeactivationToParticipantsAndRegisteredSpectatorsOnly() {
        var sent = new ArrayList<Delivery>();
        var service = new DynamaxAtmosphereService(
            new DynamaxBattleStateTracker(),
            (playerId, battleId, active) -> sent.add(new Delivery(playerId, battleId, active))
        );
        var battleId = UUID.randomUUID();
        var firstPokemon = UUID.randomUUID();
        var secondPokemon = UUID.randomUUID();
        var participant = UUID.randomUUID();
        var spectator = UUID.randomUUID();
        var nearby = UUID.randomUUID();
        var audience = BattleAudience.resolve(List.of(participant), List.of(spectator));

        service.start(battleId, firstPokemon, audience);
        assertEquals(
            List.of(new Delivery(participant, battleId, true), new Delivery(spectator, battleId, true)),
            sent
        );
        assertTrue(sent.stream().noneMatch(delivery -> delivery.playerId().equals(nearby)));

        service.start(battleId, secondPokemon, audience);
        service.end(battleId, firstPokemon, audience);
        assertEquals(2, sent.size());

        service.end(battleId, secondPokemon, audience);
        assertEquals(
            List.of(new Delivery(participant, battleId, false), new Delivery(spectator, battleId, false)),
            sent.subList(2, 4)
        );
    }

    @Test
    void synchronizesLateSpectatorsAndClearsThemWhenTheyLeave() {
        var sent = new ArrayList<Delivery>();
        var service = new DynamaxAtmosphereService(
            new DynamaxBattleStateTracker(),
            (playerId, battleId, active) -> sent.add(new Delivery(playerId, battleId, active))
        );
        var battleId = UUID.randomUUID();
        var spectator = UUID.randomUUID();

        service.start(battleId, UUID.randomUUID(), List.of());
        service.spectatorJoined(battleId, spectator);
        service.spectatorLeft(battleId, spectator);

        assertEquals(
            List.of(new Delivery(spectator, battleId, true), new Delivery(spectator, battleId, false)),
            sent
        );
    }

    @Test
    void forcedEndIsIdempotent() {
        var sent = new ArrayList<Delivery>();
        var service = new DynamaxAtmosphereService(
            new DynamaxBattleStateTracker(),
            (playerId, battleId, active) -> sent.add(new Delivery(playerId, battleId, active))
        );
        var battleId = UUID.randomUUID();
        var participant = UUID.randomUUID();

        service.start(battleId, UUID.randomUUID(), List.of(participant));
        service.forceEnd(battleId, List.of(participant));
        service.forceEnd(battleId, List.of(participant));

        assertEquals(2, sent.size());
        assertEquals(new Delivery(participant, battleId, false), sent.getLast());
    }

    @Test
    void serverShutdownClearsTrackedBattlesWithoutSendingStaleState() {
        var sent = new ArrayList<Delivery>();
        var service = new DynamaxAtmosphereService(
            new DynamaxBattleStateTracker(),
            (playerId, battleId, active) -> sent.add(new Delivery(playerId, battleId, active))
        );
        var battleId = UUID.randomUUID();
        var participant = UUID.randomUUID();
        var lateSpectator = UUID.randomUUID();

        service.start(battleId, UUID.randomUUID(), List.of(participant));
        service.clear();
        service.spectatorJoined(battleId, lateSpectator);

        assertEquals(List.of(new Delivery(participant, battleId, true)), sent);
    }

    private record Delivery(UUID playerId, UUID battleId, boolean active) {
    }
}

package jbro.cobblemon.betterbattlepresentation.server;

import java.util.Objects;
import java.util.UUID;

final class DynamaxAtmosphereService {
    private final DynamaxBattleStateTracker tracker;
    private final StateSender sender;

    DynamaxAtmosphereService(DynamaxBattleStateTracker tracker, StateSender sender) {
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.sender = Objects.requireNonNull(sender, "sender");
    }

    void start(
        UUID battleId,
        UUID pokemonId,
        Iterable<UUID> audience
    ) {
        if (tracker.start(battleId, pokemonId) == DynamaxBattleStateTracker.Transition.ACTIVATED) {
            broadcast(audience, battleId, true);
        }
    }

    void end(UUID battleId, UUID pokemonId, Iterable<UUID> audience) {
        if (tracker.end(battleId, pokemonId) == DynamaxBattleStateTracker.Transition.DEACTIVATED) {
            broadcast(audience, battleId, false);
        }
    }

    void forceEnd(UUID battleId, Iterable<UUID> audience) {
        if (tracker.forceEnd(battleId) == DynamaxBattleStateTracker.Transition.DEACTIVATED) {
            broadcast(audience, battleId, false);
        }
    }

    void spectatorJoined(UUID battleId, UUID spectatorId) {
        if (tracker.isActive(battleId)) {
            sender.send(spectatorId, battleId, true);
        }
    }

    void spectatorLeft(UUID battleId, UUID spectatorId) {
        if (tracker.isActive(battleId)) {
            sender.send(spectatorId, battleId, false);
        }
    }

    boolean isActive(UUID battleId) {
        return tracker.isActive(battleId);
    }

    void clear() {
        tracker.clear();
    }

    private void broadcast(Iterable<UUID> audience, UUID battleId, boolean active) {
        Objects.requireNonNull(audience, "audience");
        for (UUID playerId : audience) {
            sender.send(Objects.requireNonNull(playerId, "audience playerId"), battleId, active);
        }
    }

    @FunctionalInterface
    interface StateSender {
        void send(UUID playerId, UUID battleId, boolean active);
    }
}

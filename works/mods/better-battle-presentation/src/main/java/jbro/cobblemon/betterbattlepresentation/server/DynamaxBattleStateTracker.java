package jbro.cobblemon.betterbattlepresentation.server;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class DynamaxBattleStateTracker {
    private final Map<UUID, Set<UUID>> dynamaxedPokemonByBattle = new HashMap<>();

    synchronized Transition start(UUID battleId, UUID pokemonId) {
        Objects.requireNonNull(battleId, "battleId");
        Objects.requireNonNull(pokemonId, "pokemonId");

        Set<UUID> pokemonIds = dynamaxedPokemonByBattle.computeIfAbsent(battleId, ignored -> new HashSet<>());
        boolean wasEmpty = pokemonIds.isEmpty();
        boolean added = pokemonIds.add(pokemonId);
        return added && wasEmpty ? Transition.ACTIVATED : Transition.UNCHANGED;
    }

    synchronized Transition end(UUID battleId, UUID pokemonId) {
        Objects.requireNonNull(battleId, "battleId");
        Objects.requireNonNull(pokemonId, "pokemonId");

        Set<UUID> pokemonIds = dynamaxedPokemonByBattle.get(battleId);
        if (pokemonIds == null || !pokemonIds.remove(pokemonId)) {
            return Transition.UNCHANGED;
        }
        if (!pokemonIds.isEmpty()) {
            return Transition.UNCHANGED;
        }
        dynamaxedPokemonByBattle.remove(battleId);
        return Transition.DEACTIVATED;
    }

    synchronized Transition forceEnd(UUID battleId) {
        Objects.requireNonNull(battleId, "battleId");
        return dynamaxedPokemonByBattle.remove(battleId) == null
            ? Transition.UNCHANGED
            : Transition.DEACTIVATED;
    }

    synchronized boolean isActive(UUID battleId) {
        Set<UUID> pokemonIds = dynamaxedPokemonByBattle.get(Objects.requireNonNull(battleId, "battleId"));
        return pokemonIds != null && !pokemonIds.isEmpty();
    }

    synchronized void clear() {
        dynamaxedPokemonByBattle.clear();
    }

    enum Transition {
        ACTIVATED,
        DEACTIVATED,
        UNCHANGED
    }

}

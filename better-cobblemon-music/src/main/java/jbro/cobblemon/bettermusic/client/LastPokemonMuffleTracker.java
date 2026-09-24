package jbro.cobblemon.bettermusic.client;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;

public final class LastPokemonMuffleTracker {
    public static final LastPokemonMuffleTracker INSTANCE = new LastPokemonMuffleTracker();
    // Matches Cobblemon's red health-bar threshold in RenderHelper.
    private static final double RED_HEALTH_RATIO = 0.2;

    private UUID battleId;
    private final Set<UUID> observedFaintedPokemon = new HashSet<>();

    public synchronized boolean shouldMuffle(Minecraft client) {
        Objects.requireNonNull(client, "client");
        if (client.player == null) {
            clear();
            return false;
        }
        var battle = CobblemonClient.INSTANCE.getBattle();
        if (battle == null) {
            clear();
            return false;
        }
        var actor = battle.getParticipatingActor(client.player.getUUID());
        if (actor == null) {
            clear();
            return false;
        }
        List<TeamPokemon> team = actor.getPokemon().stream()
            .map(pokemon -> new TeamPokemon(pokemon.getUuid(), pokemon.isFainted()))
            .toList();
        Map<UUID, Double> activeHealthRatios = new HashMap<>();
        for (var side : battle.getSides()) {
            for (var activePokemon : side.getActiveClientBattlePokemon()) {
                if (!activePokemon.getActor().getUuid().equals(actor.getUuid())) {
                    continue;
                }
                var battlePokemon = activePokemon.getBattlePokemon();
                if (battlePokemon == null) {
                    continue;
                }
                double healthRatio = battlePokemon.isHpFlat()
                    ? battlePokemon.getMaxHp() > 0.0F
                        ? battlePokemon.getHpValue() / battlePokemon.getMaxHp()
                        : Double.NaN
                    : battlePokemon.getHpValue();
                if (Double.isFinite(healthRatio)) {
                    activeHealthRatios.put(battlePokemon.getUuid(), healthRatio);
                }
            }
        }
        return update(battle.getBattleId(), team, activeHealthRatios);
    }

    public synchronized void onFaint(Minecraft client, String pnx) {
        Objects.requireNonNull(client, "client");
        if (client.player == null || pnx == null) {
            return;
        }
        var battle = CobblemonClient.INSTANCE.getBattle();
        if (battle == null) {
            clear();
            return;
        }
        for (var side : battle.getSides()) {
            for (var activePokemon : side.getActiveClientBattlePokemon()) {
                if (!pnx.equals(activePokemon.getPNX())) {
                    continue;
                }
                var actor = activePokemon.getActor();
                var battlePokemon = activePokemon.getBattlePokemon();
                if (actor.getUuid().equals(client.player.getUUID()) && battlePokemon != null) {
                    markFainted(battle.getBattleId(), battlePokemon.getUuid());
                }
                return;
            }
        }
    }

    public synchronized void onTeamPokemonUpdate(Minecraft client, Pokemon pokemon) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(pokemon, "pokemon");
        if (client.player == null) {
            return;
        }
        var battle = CobblemonClient.INSTANCE.getBattle();
        if (battle == null) {
            clear();
            return;
        }
        var actor = battle.getParticipatingActor(client.player.getUUID());
        if (actor == null || actor.getPokemon().stream().noneMatch(
            teamPokemon -> teamPokemon.getUuid().equals(pokemon.getUuid())
        )) {
            return;
        }
        updatePokemon(
            battle.getBattleId(),
            new TeamPokemon(pokemon.getUuid(), pokemon.isFainted())
        );
    }

    synchronized boolean update(
        UUID currentBattleId,
        List<TeamPokemon> team,
        Map<UUID, Double> activeHealthRatios
    ) {
        Objects.requireNonNull(currentBattleId, "currentBattleId");
        team = List.copyOf(Objects.requireNonNull(team, "team"));
        activeHealthRatios = Map.copyOf(Objects.requireNonNull(
            activeHealthRatios,
            "activeHealthRatios"
        ));
        switchBattleIfNeeded(currentBattleId);
        List<TeamPokemon> usablePokemon = team.stream()
            .filter(pokemon -> !pokemon.fainted())
            .filter(pokemon -> !observedFaintedPokemon.contains(pokemon.id()))
            .limit(2)
            .toList();
        if (usablePokemon.size() != 1) {
            return false;
        }
        Double healthRatio = activeHealthRatios.get(usablePokemon.getFirst().id());
        return healthRatio != null
            && healthRatio > 0.0
            && healthRatio <= RED_HEALTH_RATIO;
    }

    synchronized void markFainted(UUID currentBattleId, UUID pokemonId) {
        Objects.requireNonNull(currentBattleId, "currentBattleId");
        Objects.requireNonNull(pokemonId, "pokemonId");
        switchBattleIfNeeded(currentBattleId);
        observedFaintedPokemon.add(pokemonId);
    }

    synchronized void updatePokemon(UUID currentBattleId, TeamPokemon pokemon) {
        Objects.requireNonNull(currentBattleId, "currentBattleId");
        Objects.requireNonNull(pokemon, "pokemon");
        switchBattleIfNeeded(currentBattleId);
        if (pokemon.fainted()) {
            observedFaintedPokemon.add(pokemon.id());
        } else {
            observedFaintedPokemon.remove(pokemon.id());
        }
    }

    public synchronized void clear() {
        battleId = null;
        observedFaintedPokemon.clear();
    }

    private void switchBattleIfNeeded(UUID currentBattleId) {
        if (!currentBattleId.equals(battleId)) {
            battleId = currentBattleId;
            observedFaintedPokemon.clear();
        }
    }

    record TeamPokemon(UUID id, boolean fainted) {
        TeamPokemon {
            Objects.requireNonNull(id, "id");
        }
    }
}

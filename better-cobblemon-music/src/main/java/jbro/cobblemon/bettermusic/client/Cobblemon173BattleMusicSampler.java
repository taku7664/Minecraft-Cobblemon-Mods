package jbro.cobblemon.bettermusic.client;

import com.cobblemon.mod.common.api.battles.model.actor.ActorType;
import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.battle.ClientBattle;
import com.cobblemon.mod.common.client.battle.ClientBattleActor;
import com.cobblemon.mod.common.client.battle.ClientBattlePokemon;
import com.cobblemon.mod.common.client.battle.ClientBattleSide;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import jbro.cobblemon.bettermusic.battle.BattleMusicContext;
import jbro.cobblemon.bettermusic.battle.BattleOpponentSideSelector;
import jbro.cobblemon.bettermusic.config.BattleMusicConfig;
import jbro.cobblemon.bettermusic.api.BattleMusicContentProviders;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

public final class Cobblemon173BattleMusicSampler {
    private final RctTrainerRoleAdapter rctRoles;

    public Cobblemon173BattleMusicSampler(Logger logger) {
        this.rctRoles = new RctTrainerRoleAdapter(logger);
    }

    public Optional<BattleMusicContext> sample(Minecraft client) {
        if (client.player == null) {
            return Optional.empty();
        }
        ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
        if (battle == null) {
            return Optional.empty();
        }

        ClientBattleActor participant = battle.getParticipatingActor(client.player.getUUID());
        Set<ClientBattleSide> opponentSides = new LinkedHashSet<>();
        if (participant != null) {
            for (ClientBattleSide side : battle.getSides()) {
                if (side != participant.getSide()) {
                    opponentSides.add(side);
                }
            }
        } else {
            List<ClientBattleSide> sides = List.of(battle.getSides());
            List<Set<BattleOpponentSideSelector.ActorKind>> actorKinds = new ArrayList<>();
            for (ClientBattleSide side : sides) {
                Set<BattleOpponentSideSelector.ActorKind> kinds = new LinkedHashSet<>();
                for (ClientBattleActor actor : side.getActors()) {
                    kinds.add(actorKind(actor));
                }
                actorKinds.add(kinds);
            }
            for (int index : BattleOpponentSideSelector.spectatorSides(actorKinds)) {
                opponentSides.add(sides.get(index));
            }
        }

        Set<String> species = new LinkedHashSet<>();
        Set<String> trainerRoles = new LinkedHashSet<>();
        Set<BattleMusicContext.Label> labels = new LinkedHashSet<>();
        boolean wild = false;
        boolean npc = false;
        for (ClientBattleSide side : opponentSides) {
            for (ClientBattleActor actor : side.getActors()) {
                wild |= actor.getType() == ActorType.WILD;
                npc |= actor.getType() == ActorType.NPC;
                if (actor.getType() == ActorType.NPC) {
                    rctRoles.resolve(client, actor).ifPresent(trainerRoles::add);
                }
            }
        }

        BattleMusicConfig.BattleType type = wild
            ? BattleMusicConfig.BattleType.WILD
            : npc ? BattleMusicConfig.BattleType.TRAINER : BattleMusicConfig.BattleType.PVP;
        for (ClientBattleSide side : opponentSides) {
            for (var active : side.getActiveClientBattlePokemon()) {
                collectBattlePokemon(active.getBattlePokemon(), type, species, labels);
            }
        }

        return Optional.of(new BattleMusicContext(
            type,
            species,
            trainerRoles,
            labels,
            BattleMusicContentProviders.global().resolve(battle.getBattleId())
        ));
    }

    static void collectBattlePokemon(
        ClientBattlePokemon battlePokemon,
        BattleMusicConfig.BattleType type,
        Set<String> species,
        Set<BattleMusicContext.Label> labels
    ) {
        if (battlePokemon == null) {
            return;
        }
        var pokemonSpecies = battlePokemon.getSpecies();
        if (pokemonSpecies == null) {
            return;
        }
        species.add(pokemonSpecies.getResourceIdentifier().toString());
        if (type != BattleMusicConfig.BattleType.WILD) {
            return;
        }
        Set<String> speciesLabels = pokemonSpecies.getLabels();
        if (speciesLabels.contains("ultra_beast")) {
            labels.add(BattleMusicContext.Label.ULTRA_BEAST);
        }
        if (speciesLabels.contains("legendary") || speciesLabels.contains("mythical")) {
            labels.add(BattleMusicContext.Label.LEGENDARY);
        }
    }

    private static BattleOpponentSideSelector.ActorKind actorKind(ClientBattleActor actor) {
        if (actor.getType() == ActorType.WILD) {
            return BattleOpponentSideSelector.ActorKind.WILD;
        }
        if (actor.getType() == ActorType.NPC) {
            return BattleOpponentSideSelector.ActorKind.NPC;
        }
        return BattleOpponentSideSelector.ActorKind.PLAYER;
    }
}

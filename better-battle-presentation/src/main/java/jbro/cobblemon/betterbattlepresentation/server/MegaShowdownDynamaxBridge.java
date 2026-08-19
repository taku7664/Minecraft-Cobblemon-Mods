package jbro.cobblemon.betterbattlepresentation.server;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.github.yajatkaul.mega_showdown.api.event.DynamaxEndCallback;
import com.github.yajatkaul.mega_showdown.api.event.DynamaxStartCallback;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import jbro.cobblemon.betterbattlepresentation.network.DynamaxAtmosphereNetworking;
import kotlin.Unit;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class MegaShowdownDynamaxBridge {
    private static final DynamaxAtmosphereService SERVICE = new DynamaxAtmosphereService(
        new DynamaxBattleStateTracker(),
        DynamaxAtmosphereNetworking::send
    );
    private static final Set<UUID> CLEANUP_REGISTERED = ConcurrentHashMap.newKeySet();

    private MegaShowdownDynamaxBridge() {
    }

    public static void register() {
        DynamaxStartCallback.EVENT.register(MegaShowdownDynamaxBridge::onDynamaxStart);
        DynamaxEndCallback.EVENT.register(MegaShowdownDynamaxBridge::onDynamaxEnd);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            SERVICE.clear();
            CLEANUP_REGISTERED.clear();
        });
    }

    public static void spectatorJoined(PokemonBattle battle, UUID spectatorId) {
        SERVICE.spectatorJoined(battle.getBattleId(), spectatorId);
    }

    public static void spectatorLeft(UUID battleId, UUID spectatorId) {
        SERVICE.spectatorLeft(battleId, spectatorId);
    }

    private static void onDynamaxStart(PokemonBattle battle, BattlePokemon pokemon, Boolean gigantamax) {
        UUID battleId = battle.getBattleId();
        SERVICE.start(battleId, pokemon.getUuid(), audience(battle));
        if (CLEANUP_REGISTERED.add(battleId)) {
            battle.getOnEndHandlers().add(endedBattle -> {
                SERVICE.forceEnd(battleId, audience(endedBattle));
                CLEANUP_REGISTERED.remove(battleId);
                return Unit.INSTANCE;
            });
        }
    }

    private static void onDynamaxEnd(PokemonBattle battle, BattlePokemon pokemon) {
        SERVICE.end(battle.getBattleId(), pokemon.getUuid(), audience(battle));
    }

    private static Set<UUID> audience(PokemonBattle battle) {
        var participantIds = new java.util.ArrayList<UUID>();
        battle.getPlayerUUIDs().forEach(participantIds::add);
        return BattleAudience.resolve(participantIds, battle.getSpectators());
    }
}

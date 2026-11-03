package jbro.cobblemon.battlespeed.server;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.BattleSide;
import jbro.cobblemon.battlespeed.config.BattleSpeedConfig;
import jbro.cobblemon.battlespeed.core.BattleSpeedPolicy;
import jbro.cobblemon.battlespeed.core.BattleSpeedPolicy.Kind;
import jbro.cobblemon.battlespeed.core.BattleSpeedPolicy.Participant;
import jbro.cobblemon.battlespeed.core.SpeedScope;
import jbro.cobblemon.battlespeed.network.BattleSpeedPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** Server-side state of the mod and the speed every running battle gets from it. */
public final class BattleSpeedService {
    private static volatile BattleSpeedConfig config = new BattleSpeedConfig().normalized();
    private static volatile PlayerSpeedStore store = new PlayerSpeedStore(null);
    private static volatile MinecraftServer server;

    private BattleSpeedService() {
    }

    public static BattleSpeedConfig config() {
        return config;
    }

    public static void setConfig(BattleSpeedConfig newConfig) {
        config = newConfig;
    }

    public static PlayerSpeedStore store() {
        return store;
    }

    public static void attach(MinecraftServer newServer, PlayerSpeedStore newStore) {
        server = newServer;
        store = newStore;
    }

    public static void detach(MinecraftServer oldServer) {
        if (server == oldServer) {
            server = null;
            store = new PlayerSpeedStore(null);
        }
    }

    public static int levelOf(UUID player) {
        var current = config;
        return current.clampLevel(store.level(player).orElse(current.defaultLevel));
    }

    public static List<List<Participant>> sidesOf(PokemonBattle battle) {
        var sides = new ArrayList<List<Participant>>(2);
        for (BattleSide side : battle.getSides()) {
            var participants = new ArrayList<Participant>();
            for (BattleActor actor : side.getActors()) {
                participants.add(switch (actor.getType()) {
                    case PLAYER -> {
                        var ids = new ArrayList<UUID>();
                        actor.getPlayerUUIDs().forEach(ids::add);
                        yield new Participant(Kind.PLAYER, ids);
                    }
                    case WILD -> Participant.wild();
                    case NPC -> Participant.npc();
                });
            }
            sides.add(participants);
        }
        return sides;
    }

    public static boolean eligible(PokemonBattle battle) {
        return BattleSpeedPolicy.eligible(config, sidesOf(battle));
    }

    public static double levelSpeed(PokemonBattle battle) {
        return BattleSpeedPolicy.levelSpeed(config, sidesOf(battle), BattleSpeedService::levelOf);
    }

    /** Speed for a fixed pause after a battle message created right now. */
    public static double messageWaitSpeed() {
        var battle = currentBattle();
        return battle == null ? 1.0 : config.apply.messageWaits.factor(levelSpeed(battle));
    }

    /** Speed for a server task scheduled right now: move animation delays or other battle timers. */
    public static double scheduledTaskSpeed() {
        var frame = SpeedScope.current();
        if (frame == null || !(frame.battle() instanceof PokemonBattle battle)) {
            return 1.0;
        }
        var part = frame.kind() == SpeedScope.Kind.MOVE_EFFECT ? config.apply.moveEffects : config.apply.battleTimers;
        return part.factor(levelSpeed(battle));
    }

    public static boolean skipMoveEffects(PokemonBattle battle) {
        return BattleSpeedPolicy.skipMoveEffects(config, levelSpeed(battle));
    }

    /** Sends the battle's HUD animation speed to everyone watching it. */
    public static void broadcast(PokemonBattle battle) {
        var audience = new LinkedHashSet<UUID>();
        battle.getPlayerUUIDs().forEach(audience::add);
        audience.addAll(battle.getSpectators());
        for (UUID id : audience) {
            send(id, battle);
        }
    }

    /** Resends every running battle's speed, for after a config reload. */
    public static void broadcastAll() {
        MinecraftServer current = server;
        if (current == null) {
            return;
        }
        var seen = new LinkedHashSet<UUID>();
        for (ServerPlayer player : current.getPlayerList().getPlayers()) {
            var battle = BattleRegistry.getBattleByParticipatingPlayer(player);
            if (battle != null && seen.add(battle.getBattleId())) {
                broadcast(battle);
            }
        }
    }

    public static void send(UUID playerId, PokemonBattle battle) {
        MinecraftServer current = server;
        if (current == null) {
            return;
        }
        ServerPlayer player = current.getPlayerList().getPlayer(playerId);
        if (player == null || !ServerPlayNetworking.canSend(player, BattleSpeedPayload.TYPE)) {
            return;
        }
        float speed = (float) config.apply.clientAnimations.factor(levelSpeed(battle));
        ServerPlayNetworking.send(player, new BattleSpeedPayload(battle.getBattleId(), speed));
    }

    private static PokemonBattle currentBattle() {
        var frame = SpeedScope.current();
        return frame != null && frame.battle() instanceof PokemonBattle battle ? battle : null;
    }
}

package jbro.cobblemon.betterbattlepresentation.network;

import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class DynamaxAtmosphereNetworking {
    private static volatile MinecraftServer currentServer;

    private DynamaxAtmosphereNetworking() {
    }

    public static void registerServer() {
        PayloadTypeRegistry.playS2C().register(DynamaxAtmospherePayload.TYPE, DynamaxAtmospherePayload.CODEC);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> currentServer = server);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (currentServer == server) {
                currentServer = null;
            }
        });
    }

    public static void send(UUID playerId, UUID battleId, boolean active) {
        MinecraftServer server = currentServer;
        if (server == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null && ServerPlayNetworking.canSend(player, DynamaxAtmospherePayload.TYPE)) {
            ServerPlayNetworking.send(player, new DynamaxAtmospherePayload(battleId, active));
        }
    }
}

package jbro.cobblemon.battlespeed.client;

import jbro.cobblemon.battlespeed.network.BattleSpeedPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class BattleSpeedClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(BattleSpeedPayload.TYPE, (payload, context) ->
            context.client().execute(() -> ClientBattleSpeedState.set(payload.battleId(), payload.animationSpeed()))
        );
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientBattleSpeedState.clear());
    }
}

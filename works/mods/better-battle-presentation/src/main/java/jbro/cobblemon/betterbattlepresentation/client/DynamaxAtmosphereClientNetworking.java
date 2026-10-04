package jbro.cobblemon.betterbattlepresentation.client;

import jbro.cobblemon.betterbattlepresentation.network.DynamaxAtmospherePayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

final class DynamaxAtmosphereClientNetworking {
    private DynamaxAtmosphereClientNetworking() {
    }

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(DynamaxAtmospherePayload.TYPE, (payload, context) ->
            context.client().execute(() ->
                DynamaxAtmosphereClientState.setActive(payload.battleId(), payload.active())
            )
        );
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
            DynamaxAtmosphereClientState.clear()
        );
        ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register((client, world) ->
            DynamaxAtmosphereClientState.clear()
        );
    }
}

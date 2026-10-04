package jbro.cobblemon.popupemotes.network;

import java.util.LinkedHashSet;
import jbro.cobblemon.popupemotes.PlayerPopupEmotes;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

public final class EmoteNetworking {
    private static final EmoteRateLimiter RATE_LIMITER = new EmoteRateLimiter();

    private EmoteNetworking() {
    }

    public static void registerServer() {
        PayloadTypeRegistry.playC2S().register(UseEmotePayload.TYPE, UseEmotePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ShowEmotePayload.TYPE, ShowEmotePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(UseEmotePayload.TYPE, (payload, context) ->
            handle(context.player(), payload)
        );
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            RATE_LIMITER.remove(handler.player.getUUID())
        );
    }

    private static void handle(ServerPlayer sender, UseEmotePayload payload) {
        if (!EmoteRequestPolicy.accepts(payload.emoteId(), payload.heightOffset())) {
            PlayerPopupEmotes.LOGGER.debug("Rejected invalid emote request from {}", sender.getGameProfile().getName());
            return;
        }
        if (!RATE_LIMITER.tryAcquire(sender.getUUID(), System.nanoTime())) {
            return;
        }

        var outgoing = new ShowEmotePayload(sender.getUUID(), payload.emoteId(), payload.heightOffset());
        var recipients = new LinkedHashSet<ServerPlayer>(PlayerLookup.tracking(sender));
        recipients.add(sender);
        for (ServerPlayer recipient : recipients) {
            if (ServerPlayNetworking.canSend(recipient, ShowEmotePayload.TYPE)) {
                ServerPlayNetworking.send(recipient, outgoing);
            }
        }
    }
}

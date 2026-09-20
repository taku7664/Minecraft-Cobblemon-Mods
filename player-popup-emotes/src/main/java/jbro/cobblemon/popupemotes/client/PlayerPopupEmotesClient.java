package jbro.cobblemon.popupemotes.client;

import com.mojang.blaze3d.platform.InputConstants;
import jbro.cobblemon.popupemotes.client.custom.CustomEmoteConfigStore;
import jbro.cobblemon.popupemotes.client.custom.CustomEmotesScreenAccess;
import jbro.cobblemon.popupemotes.client.custom.RemoteEmoteManager;
import jbro.cobblemon.popupemotes.network.ShowEmotePayload;
import jbro.cobblemon.popupemotes.network.UseEmotePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.KeyMapping;

public final class PlayerPopupEmotesClient implements ClientModInitializer {
    private static final KeyMapping EMOTE_WHEEL_KEY = new KeyMapping(
        "key.player_popup_emotes.wheel",
        InputConstants.Type.KEYSYM,
        InputConstants.KEY_V,
        "key.categories.player_popup_emotes"
    );

    @Override
    public void onInitializeClient() {
        CustomEmoteConfigStore.load();
        CustomEmotesScreenAccess.register();
        KeyBindingHelper.registerKeyBinding(EMOTE_WHEEL_KEY);
        ClientPlayNetworking.registerGlobalReceiver(ShowEmotePayload.TYPE, (payload, context) ->
            context.client().execute(() -> EmoteRenderer.show(payload))
        );
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            EmoteWheel.tick(client, EMOTE_WHEEL_KEY);
            EmoteRenderer.tick(client);
        });
        HudRenderCallback.EVENT.register(EmoteWheel::render);
        WorldRenderEvents.LAST.register(EmoteRenderer::render);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            EmoteWheel.reset(client);
            EmoteRenderer.clear();
            RemoteEmoteManager.closeAll();
        });
    }

    static void send(String emoteId) {
        if (ClientPlayNetworking.canSend(UseEmotePayload.TYPE)) {
            ClientPlayNetworking.send(new UseEmotePayload(emoteId, 0.0F));
        }
    }
}

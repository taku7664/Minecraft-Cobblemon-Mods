package jbro.minecraft.simpletrashcan;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

public final class SimpleTrashCanMod implements ModInitializer {
    @Override
    public void onInitialize() {
        // The trash is never saved, so whatever is in it when the player leaves is gone.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            TrashLog.deleted(handler.player, ((TrashHolder) handler.player).simpleTrashCan$trash().getItem(0), TrashLog.Reason.DISCONNECT));
    }
}

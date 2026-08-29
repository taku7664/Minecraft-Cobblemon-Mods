package jbro.minecraft.roundingblock.client;

import net.fabricmc.api.ClientModInitializer;

public final class RoundingBlockClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        RoundingBlockModelPlugin.register();
    }
}

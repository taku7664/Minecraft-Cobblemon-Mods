package jbro.cobblemon.betterbattlepresentation.client;

import net.fabricmc.api.ClientModInitializer;

public final class BetterBattlePresentationClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        DynamaxAtmosphereClientNetworking.register();
        DynamaxSkyShader.register();
        DynamaxWorldGradeShader.register();
    }
}

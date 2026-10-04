package jbro.cobblemon.betterbattlepresentation;

import jbro.cobblemon.betterbattlepresentation.network.DynamaxAtmosphereNetworking;
import jbro.cobblemon.betterbattlepresentation.server.MegaShowdownDynamaxBridge;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BetterBattlePresentation implements ModInitializer {
    public static final String MOD_ID = "cobblemon_better_battle_presentation";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        DynamaxAtmosphereNetworking.registerServer();
        MegaShowdownDynamaxBridge.register();
        LOGGER.info("Initializing Cobblemon: Better Battle Presentation");
    }
}

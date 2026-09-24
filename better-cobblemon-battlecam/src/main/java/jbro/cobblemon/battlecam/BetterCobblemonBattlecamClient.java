package jbro.cobblemon.battlecam;

import com.batmite2b.battlecam.client.BattleCamClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BetterCobblemonBattlecamClient implements ClientModInitializer {
    public static final String MOD_ID = "better_cobblemon_battlecam";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        BattlecamConfigStore.initialize(FabricLoader.getInstance().getConfigDir());
        new BattleCamClient().onInitializeClient();
        LOGGER.info("Better Cobblemon Battlecam 2.0.3 port initialized for Cobblemon 1.8.1");
    }
}

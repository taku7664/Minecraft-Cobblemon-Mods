package jbro.cobblemon.bettermusic;

import jbro.cobblemon.bettermusic.client.BetterMusicClientCommands;
import jbro.cobblemon.bettermusic.client.BetterMusicClientRuntime;
import jbro.cobblemon.bettermusic.client.GeneratedMusicPackController;
import jbro.cobblemon.bettermusic.config.BetterMusicConfigManager;
import jbro.cobblemon.bettermusic.integration.mbc.MoreBattleContentIntegration;
import jbro.cobblemon.bettermusic.resource.GeneratedMusicResourcePack;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BetterCobblemonMusicClient implements ClientModInitializer {
    public static final String MOD_ID = "better_cobblemon_music";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
            jbro.cobblemon.bettermusic.audio.ClientHitSoundTracker.INSTANCE.clear()
        );
        MoreBattleContentIntegration.registerIfInstalled(LOGGER);
        var configDirectory = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        var configManager = new BetterMusicConfigManager(configDirectory);
        var initialLoad = configManager.initialize();
        logInitialLoad(initialLoad);
        var generatedPack = new GeneratedMusicResourcePack(
            configDirectory,
            FabricLoader.getInstance().getGameDir().resolve("resourcepacks")
        );
        var packController = new GeneratedMusicPackController(generatedPack, LOGGER);
        configManager.activeSnapshot().ifPresent(packController::prepare);
        packController.registerActivation();
        BetterMusicClientCommands.register(configManager, packController);
        new BetterMusicClientRuntime(configManager, LOGGER).register();
    }

    private static void logInitialLoad(BetterMusicConfigManager.ReloadResult result) {
        switch (result.outcome()) {
            case APPLIED -> LOGGER.info(result.message());
            case RETAINED_LAST_GOOD -> LOGGER.warn(result.message());
            case FALLBACK_TO_BUNDLED -> LOGGER.warn(result.message());
            case NO_VALID_CONFIG -> LOGGER.error(result.message());
        }
    }
}

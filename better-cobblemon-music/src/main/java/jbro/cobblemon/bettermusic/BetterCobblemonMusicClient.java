package jbro.cobblemon.bettermusic;

import jbro.cobblemon.bettermusic.client.BetterMusicClientCommands;
import jbro.cobblemon.bettermusic.client.BetterMusicClientRuntime;
import jbro.cobblemon.bettermusic.client.MusicCatalogResourceReloadListener;
import jbro.cobblemon.bettermusic.config.BetterMusicConfigManager;
import jbro.cobblemon.bettermusic.integration.mbc.MoreBattleContentIntegration;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.packs.PackType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BetterCobblemonMusicClient implements ClientModInitializer {
    public static final String MOD_ID = "better_cobblemon_music";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            jbro.cobblemon.bettermusic.audio.ClientHitSoundTracker.INSTANCE.clear();
            jbro.cobblemon.bettermusic.client.LastPokemonMuffleTracker.INSTANCE.clear();
        });
        MoreBattleContentIntegration.registerIfInstalled(LOGGER);
        var configDirectory = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        var configManager = new BetterMusicConfigManager(configDirectory);
        var initialLoad = configManager.initialize();
        logInitialLoad(initialLoad);
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
            new MusicCatalogResourceReloadListener(configManager, LOGGER)
        );
        BetterMusicClientCommands.register(configManager);
        new BetterMusicClientRuntime(configManager, LOGGER).register();
    }

    private static void logInitialLoad(BetterMusicConfigManager.ReloadResult result) {
        switch (result.outcome()) {
            case INITIALIZED -> LOGGER.info(result.message());
            case APPLIED -> LOGGER.info(result.message());
            case RETAINED_LAST_GOOD -> LOGGER.warn(result.message());
            case NO_VALID_CONFIG -> LOGGER.error(result.message());
        }
    }
}

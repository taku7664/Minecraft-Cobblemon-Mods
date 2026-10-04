package jbro.cobblemon.bettermusic.integration.mcc;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

/**
 * Built-in music for More Cobblemon Contents: content battles by stage (gym, Elite Four, Champion, Tower bosses, ...)
 * and the battle hub's tabs. MCC is optional; without it, or with an MCC too old for its client API, nothing changes.
 */
public final class MoreCobblemonContentsIntegration {
    static final String MCC_MOD_ID = "more_cobblemon_contents";
    static final String PROVIDER_ID = "better_cobblemon_music:more_cobblemon_contents";

    private MoreCobblemonContentsIntegration() {
    }

    public static void registerIfInstalled(Logger logger) {
        if (!FabricLoader.getInstance().isModLoaded(MCC_MOD_ID)) {
            return;
        }

        try {
            var registration = MccMusicProviders.register(PROVIDER_ID);
            if (registration.battle() && registration.screen()) {
                logger.info("Enabled built-in More Cobblemon Contents music integration");
            } else {
                logger.warn("More Cobblemon Contents music integration was already registered");
            }
        } catch (LinkageError failure) {
            logger.warn(
                "More Cobblemon Contents is installed, but it is too old for the music integration; using normal music",
                failure
            );
        }
    }
}

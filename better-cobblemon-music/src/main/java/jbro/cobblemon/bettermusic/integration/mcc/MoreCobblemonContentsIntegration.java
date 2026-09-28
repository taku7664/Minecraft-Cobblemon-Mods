package jbro.cobblemon.bettermusic.integration.mcc;

import jbro.cobblemon.bettermusic.api.BattleMusicContentProviders;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

public final class MoreCobblemonContentsIntegration {
    static final String MCC_MOD_ID = "more_cobblemon_contents";
    static final String PROVIDER_ID = "better_cobblemon_music:more_cobblemon_contents";
    static final String CLIENT_API_CLASS =
        "jbro.cobblemon.mcc.api.presentation.ManagedBattleContentClient";

    private MoreCobblemonContentsIntegration() {
    }

    public static void registerIfInstalled(Logger logger) {
        if (!FabricLoader.getInstance().isModLoaded(MCC_MOD_ID)) {
            return;
        }

        try {
            var lookup = ReflectiveContentLookup.load(
                MoreCobblemonContentsIntegration.class.getClassLoader(),
                CLIENT_API_CLASS
            );
            var status = BattleMusicContentProviders.global().register(PROVIDER_ID, lookup::contentId);
            if (status == BattleMusicContentProviders.RegistrationStatus.REGISTERED) {
                logger.info("Enabled built-in More Cobblemon Contents music integration");
            } else {
                logger.warn("More Cobblemon Contents music integration was already registered");
            }
        } catch (ReflectiveOperationException | LinkageError failure) {
            logger.warn(
                "More Cobblemon Contents is installed, but its music content API is unavailable; using normal battle music",
                failure
            );
        }
    }
}

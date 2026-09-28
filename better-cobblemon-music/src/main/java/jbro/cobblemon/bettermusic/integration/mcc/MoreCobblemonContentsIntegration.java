package jbro.cobblemon.bettermusic.integration.mbc;

import jbro.cobblemon.bettermusic.api.BattleMusicContentProviders;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

public final class MoreBattleContentIntegration {
    static final String MBC_MOD_ID = "cobblemon_more_battle_content";
    static final String PROVIDER_ID = "better_cobblemon_music:more_battle_content";
    static final String CLIENT_API_CLASS =
        "jbro.cobblemon.morebattlecontent.api.presentation.ManagedBattleContentClient";

    private MoreBattleContentIntegration() {
    }

    public static void registerIfInstalled(Logger logger) {
        if (!FabricLoader.getInstance().isModLoaded(MBC_MOD_ID)) {
            return;
        }

        try {
            var lookup = ReflectiveMbcContentLookup.load(
                MoreBattleContentIntegration.class.getClassLoader(),
                CLIENT_API_CLASS
            );
            var status = BattleMusicContentProviders.global().register(PROVIDER_ID, lookup::contentId);
            if (status == BattleMusicContentProviders.RegistrationStatus.REGISTERED) {
                logger.info("Enabled built-in More Battle Content music integration");
            } else {
                logger.warn("More Battle Content music integration was already registered");
            }
        } catch (ReflectiveOperationException | LinkageError failure) {
            logger.warn(
                "More Battle Content is installed, but its music content API is unavailable; using normal battle music",
                failure
            );
        }
    }
}

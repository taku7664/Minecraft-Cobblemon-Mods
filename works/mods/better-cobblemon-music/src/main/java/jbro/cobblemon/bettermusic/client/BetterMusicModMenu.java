package jbro.cobblemon.bettermusic.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

public final class BetterMusicModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        if (!FabricLoader.getInstance().isModLoaded("cloth-config")) {
            return BetterMusicConfigProblemScreen::missingClothConfig;
        }
        return BetterMusicConfigScreen::create;
    }
}

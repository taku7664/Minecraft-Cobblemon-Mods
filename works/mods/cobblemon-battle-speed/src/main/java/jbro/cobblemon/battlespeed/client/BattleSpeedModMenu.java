package jbro.cobblemon.battlespeed.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

/** Mod Menu's settings button. The Cloth Config screen class is only touched when Cloth Config is installed. */
public final class BattleSpeedModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        if (!FabricLoader.getInstance().isModLoaded("cloth-config")) {
            return BattleSpeedMissingClothScreen::new;
        }
        return BattleSpeedConfigScreen::create;
    }
}

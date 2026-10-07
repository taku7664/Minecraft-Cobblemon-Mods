package jbro.minecraft.roundingblock.client.settings;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Optional shortcut to the native settings screen; Mod Menu is not required. */
public final class RoundingBlockModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new RoundingBlockConfigScreen(parent);
    }
}

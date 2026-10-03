package jbro.cobblemon.bettermusic.client;

import java.util.List;
import java.util.Set;
import jbro.cobblemon.bettermusic.api.ScreenMusicProvider;
import jbro.cobblemon.bettermusic.api.ScreenMusicProviders;
import jbro.cobblemon.bettermusic.screen.MenuMusicKeys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

public final class MinecraftMenuMusicProvider implements ScreenMusicProvider {
    public static void register() {
        ScreenMusicProviders.global().register("better_cobblemon_music:menu", new MinecraftMenuMusicProvider());
    }

    @Override
    public List<String> screenKeys() {
        return keys(Minecraft.getInstance());
    }

    public static List<String> keys(Minecraft client) {
        return MenuMusicKeys.resolve(client.player != null && client.level != null, client.screen instanceof TitleScreen);
    }

    @Override
    public Set<String> knownScreenKeys() {
        return Set.of(MenuMusicKeys.TITLE);
    }
}

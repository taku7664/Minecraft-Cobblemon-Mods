package jbro.cobblemon.bettermusic.screen;

import java.util.List;

/** Built-in menu key; child menus deliberately keep vanilla behavior. */
public final class MenuMusicKeys {
    public static final String TITLE = "minecraft:title";

    private MenuMusicKeys() {
    }

    public static List<String> resolve(boolean inWorld, boolean titleScreen) {
        return !inWorld && titleScreen ? List.of(TITLE) : List.of();
    }
}

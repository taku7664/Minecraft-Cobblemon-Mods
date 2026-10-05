package com.batmite2b.battlecam.client;

import net.minecraft.client.gui.screens.Screen;

public final class BattleScreenUtil {
    private BattleScreenUtil() {
    }

    public static boolean isCobblemonBattleScreenOpen(Screen screen) {
        if (screen == null) {
            return false;
        }
        String className = screen.getClass().getName().toLowerCase();
        return className.contains("cobblemon") && (className.contains("battle") || className.contains("fight"));
    }
}

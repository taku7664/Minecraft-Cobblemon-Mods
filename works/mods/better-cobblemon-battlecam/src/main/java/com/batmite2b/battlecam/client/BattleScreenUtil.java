/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.minecraft.client.gui.screen.Screen
 */
package com.batmite2b.battlecam.client;

import net.minecraft.client.gui.screen.Screen;

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

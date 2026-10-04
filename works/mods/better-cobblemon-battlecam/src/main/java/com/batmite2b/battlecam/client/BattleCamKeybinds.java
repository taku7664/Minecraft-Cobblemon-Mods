/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
 *  net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
 *  net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
 *  net.minecraft.client.gui.screen.Screen
 *  net.minecraft.client.option.KeyBinding
 *  net.minecraft.client.util.InputUtil$Type
 */
package com.batmite2b.battlecam.client;

import com.batmite2b.battlecam.client.BattleCamClient;
import com.batmite2b.battlecam.client.BattleCamState;
import com.batmite2b.battlecam.client.BattleScreenUtil;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

public final class BattleCamKeybinds {
    private static final long SCREEN_KEY_DEBOUNCE_MS = 250L;
    public static KeyBinding TOGGLE_OWN_BODY;
    public static KeyBinding CYCLE_MODE;
    public static KeyBinding NEXT_SHOT;
    public static KeyBinding PREV_SHOT;
    private static long lastScreenCycleModeAtMs;

    private BattleCamKeybinds() {
    }

    public static void register() {
        TOGGLE_OWN_BODY = KeyBindingHelper.registerKeyBinding((KeyBinding)new KeyBinding("key.battlecam.toggle_own_body", InputUtil.Type.KEYSYM, 298, "category.battlecam"));
        CYCLE_MODE = KeyBindingHelper.registerKeyBinding((KeyBinding)new KeyBinding("key.battlecam.cycle_mode", InputUtil.Type.KEYSYM, 295, "category.battlecam"));
        NEXT_SHOT = KeyBindingHelper.registerKeyBinding((KeyBinding)new KeyBinding("key.battlecam.next_shot", InputUtil.Type.KEYSYM, 297, "category.battlecam"));
        PREV_SHOT = KeyBindingHelper.registerKeyBinding((KeyBinding)new KeyBinding("key.battlecam.prev_shot", InputUtil.Type.KEYSYM, 296, "category.battlecam"));
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> ScreenKeyboardEvents.allowKeyPress((Screen)screen).register((currentScreen, key, scancode, modifiers) -> {
            if (key != 295 || !BattleCamClient.STATE.isBattleContextActive()) {
                return true;
            }
            if (!BattleScreenUtil.isCobblemonBattleScreenOpen(currentScreen)) {
                return true;
            }
            long now = System.currentTimeMillis();
            if (now - lastScreenCycleModeAtMs >= 250L) {
                lastScreenCycleModeAtMs = now;
                BattleCamClient.STATE.cycleMode();
            }
            return false;
        }));
    }

    public static void handleInput(BattleCamState state) {
        while (TOGGLE_OWN_BODY.wasPressed()) {
            state.toggleOwnBody();
        }
        while (CYCLE_MODE.wasPressed()) {
            state.cycleMode();
        }
        while (NEXT_SHOT.wasPressed()) {
            if (state.mode != BattleCamState.Mode.MANUAL) continue;
            state.nextShot();
        }
        while (PREV_SHOT.wasPressed()) {
            if (state.mode != BattleCamState.Mode.MANUAL) continue;
            state.previousShot();
        }
    }

    static {
        lastScreenCycleModeAtMs = 0L;
    }
}

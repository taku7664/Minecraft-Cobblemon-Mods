package com.batmite2b.battlecam.client;

import com.batmite2b.battlecam.client.BattleCamClient;
import com.batmite2b.battlecam.client.BattleCamState;
import com.batmite2b.battlecam.client.BattleScreenUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;

public final class BattleCamKeybinds {
    private static final long SCREEN_KEY_DEBOUNCE_MS = 250L;
    public static KeyMapping TOGGLE_OWN_BODY;
    public static KeyMapping CYCLE_MODE;
    public static KeyMapping NEXT_SHOT;
    public static KeyMapping PREV_SHOT;
    private static long lastScreenCycleModeAtMs;

    private BattleCamKeybinds() {
    }

    public static void register() {
        TOGGLE_OWN_BODY = KeyBindingHelper.registerKeyBinding((KeyMapping)new KeyMapping("key.battlecam.toggle_own_body", InputConstants.Type.KEYSYM, 298, "category.battlecam"));
        CYCLE_MODE = KeyBindingHelper.registerKeyBinding((KeyMapping)new KeyMapping("key.battlecam.cycle_mode", InputConstants.Type.KEYSYM, 295, "category.battlecam"));
        NEXT_SHOT = KeyBindingHelper.registerKeyBinding((KeyMapping)new KeyMapping("key.battlecam.next_shot", InputConstants.Type.KEYSYM, 297, "category.battlecam"));
        PREV_SHOT = KeyBindingHelper.registerKeyBinding((KeyMapping)new KeyMapping("key.battlecam.prev_shot", InputConstants.Type.KEYSYM, 296, "category.battlecam"));
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
        while (TOGGLE_OWN_BODY.consumeClick()) {
            state.toggleOwnBody();
        }
        while (CYCLE_MODE.consumeClick()) {
            state.cycleMode();
        }
        while (NEXT_SHOT.consumeClick()) {
            if (state.mode != BattleCamState.Mode.MANUAL) continue;
            state.nextShot();
        }
        while (PREV_SHOT.consumeClick()) {
            if (state.mode != BattleCamState.Mode.MANUAL) continue;
            state.previousShot();
        }
    }

    static {
        lastScreenCycleModeAtMs = 0L;
    }
}

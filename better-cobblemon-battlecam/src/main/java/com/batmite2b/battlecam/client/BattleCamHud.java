/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.minecraft.client.MinecraftClient
 *  net.minecraft.client.gui.DrawContext
 */
package com.batmite2b.battlecam.client;

import com.batmite2b.battlecam.client.BattleCamClient;
import com.batmite2b.battlecam.client.BattleScreenUtil;
import com.batmite2b.battlecam.client.BattleViewContext;
import java.util.Objects;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

public final class BattleCamHud {
    private static final int PADDING_X = 8;
    private static final int PADDING_BOTTOM = 8;
    private static final int LINE_SPACING = 12;

    private BattleCamHud() {
    }

    public static void render(DrawContext context) {
        if (!BattleCamClient.STATE.shouldShowHud()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.textRenderer == null) {
            return;
        }
        if (BattleScreenUtil.isCobblemonBattleScreenOpen(client.currentScreen)) {
            return;
        }
        String line1 = BattleCamClient.STATE.getHudText();
        String line2 = BattleCamClient.STATE.context == BattleViewContext.OWN_BATTLE ? "F6: Mode | F9: Body | F7/F8: Shots" : "F6: Mode | F7/F8: Shots";
        int screenHeight = context.getScaledWindowHeight();
        Objects.requireNonNull(client.textRenderer);
        int yLine2 = screenHeight - 8 - 9;
        int yLine1 = yLine2 - 12;
        context.drawTextWithShadow(client.textRenderer, line1, 8, yLine1, 0xFFFFFF);
        context.drawTextWithShadow(client.textRenderer, line2, 8, yLine2, 0xAAAAAA);
    }
}

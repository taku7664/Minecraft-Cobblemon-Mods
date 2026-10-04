/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.minecraft.client.MinecraftClient
 *  net.minecraft.client.option.Perspective
 */
package com.batmite2b.battlecam.client;

import com.batmite2b.battlecam.client.BattleCamClient;
import com.batmite2b.battlecam.client.BattleCamState;
import com.batmite2b.battlecam.client.BattleViewContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;

public final class BattleCamPerspectiveController {
    private static boolean forced = false;
    private static Perspective savedPerspective = Perspective.FIRST_PERSON;

    private BattleCamPerspectiveController() {
    }

    public static void update(MinecraftClient client) {
        boolean ownBattleActive;
        if (client == null || client.options == null) {
            return;
        }
        BattleCamState state = BattleCamClient.STATE;
        boolean bl = ownBattleActive = state.context == BattleViewContext.OWN_BATTLE && state.shouldOverrideCamera();
        if (ownBattleActive) {
            Perspective desired;
            Perspective perspective = desired = state.showOwnBody ? Perspective.THIRD_PERSON_BACK : Perspective.FIRST_PERSON;
            if (!forced) {
                savedPerspective = client.options.getPerspective();
                forced = true;
            }
            if (client.options.getPerspective() != desired) {
                client.options.setPerspective(desired);
            }
            return;
        }
        if (forced) {
            if (client.options.getPerspective() != savedPerspective) {
                client.options.setPerspective(savedPerspective);
            }
            forced = false;
        }
    }

    public static void reset(MinecraftClient client) {
        if (client == null || client.options == null) {
            forced = false;
            return;
        }
        if (forced && client.options.getPerspective() != savedPerspective) {
            client.options.setPerspective(savedPerspective);
        }
        forced = false;
    }
}

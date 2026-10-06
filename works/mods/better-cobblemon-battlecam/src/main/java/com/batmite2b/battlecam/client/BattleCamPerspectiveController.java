package com.batmite2b.battlecam.client;

import com.batmite2b.battlecam.client.BattleCamClient;
import com.batmite2b.battlecam.client.BattleCamState;
import com.batmite2b.battlecam.client.BattleViewContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

public final class BattleCamPerspectiveController {
    private static boolean forced = false;
    private static CameraType savedPerspective = CameraType.FIRST_PERSON;

    private BattleCamPerspectiveController() {
    }

    public static void update(Minecraft client) {
        boolean ownBattleActive;
        if (client == null || client.options == null) {
            return;
        }
        BattleCamState state = BattleCamClient.STATE;
        boolean scene = state.isSceneActive() && state.shouldOverrideCamera();
        boolean bl = ownBattleActive = scene || state.context == BattleViewContext.OWN_BATTLE && state.shouldOverrideCamera();
        if (ownBattleActive) {
            CameraType desired;
            // A scene may look at the player, whose body only draws outside first person.
            CameraType perspective = desired = scene || state.showOwnBody ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON;
            if (!forced) {
                savedPerspective = client.options.getCameraType();
                forced = true;
            }
            if (client.options.getCameraType() != desired) {
                client.options.setCameraType(desired);
            }
            return;
        }
        if (forced) {
            if (client.options.getCameraType() != savedPerspective) {
                client.options.setCameraType(savedPerspective);
            }
            forced = false;
        }
    }

    public static void reset(Minecraft client) {
        if (client == null || client.options == null) {
            forced = false;
            return;
        }
        if (forced && client.options.getCameraType() != savedPerspective) {
            client.options.setCameraType(savedPerspective);
        }
        forced = false;
    }
}

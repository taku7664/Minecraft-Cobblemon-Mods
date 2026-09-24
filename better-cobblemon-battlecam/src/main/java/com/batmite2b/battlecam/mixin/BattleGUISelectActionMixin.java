/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.cobblemon.mod.common.battles.MoveActionResponse
 *  com.cobblemon.mod.common.battles.ShowdownActionResponse
 *  com.cobblemon.mod.common.client.battle.SingleActionRequest
 *  com.cobblemon.mod.common.client.gui.battle.BattleGUI
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.injection.At
 *  org.spongepowered.asm.mixin.injection.Inject
 *  org.spongepowered.asm.mixin.injection.callback.CallbackInfo
 */
package com.batmite2b.battlecam.mixin;

import com.batmite2b.battlecam.client.BattleCamClient;
import com.batmite2b.battlecam.client.GimmickEvent;
import com.batmite2b.battlecam.client.GimmickKind;
import com.cobblemon.mod.common.battles.MoveActionResponse;
import com.cobblemon.mod.common.battles.ShowdownActionResponse;
import com.cobblemon.mod.common.client.battle.SingleActionRequest;
import com.cobblemon.mod.common.client.gui.battle.BattleGUI;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={BattleGUI.class})
public abstract class BattleGUISelectActionMixin {
    @Inject(method={"selectAction(Lcom/cobblemon/mod/common/client/battle/SingleActionRequest;Lcom/cobblemon/mod/common/battles/ShowdownActionResponse;)V"}, at={@At(value="HEAD")}, remap=false)
    private void battlecam$queueBattleGimmickSceneEarly(SingleActionRequest request, ShowdownActionResponse response, CallbackInfo ci) {
        if (!(response instanceof MoveActionResponse)) {
            return;
        }
        MoveActionResponse moveResponse = (MoveActionResponse)response;
        String gimmickId = moveResponse.getGimmickID();
        if (gimmickId == null || gimmickId.isBlank()) {
            return;
        }
        GimmickKind kind = switch (gimmickId) {
            case "mega" -> GimmickKind.MEGA;
            case "ultra" -> GimmickKind.ULTRA_BURST;
            case "terastal" -> GimmickKind.TERA;
            case "max" -> GimmickKind.DYNAMAX;
            default -> null;
        };
        if (kind == null) {
            return;
        }
        UUID uuid = null;
        try {
            uuid = request.getActivePokemon().getBattlePokemon().getUuid();
        }
        catch (Exception exception) {
            // empty catch block
        }
        BattleCamClient.STATE.enqueueGimmickEvent(new GimmickEvent(uuid, kind, System.currentTimeMillis() + 1800L));
    }
}

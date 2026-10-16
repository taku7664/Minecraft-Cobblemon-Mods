package jbro.cobblemon.ui.extended.mixin;

import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleGimmickButton;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The owning move selection draws the replacement tile after Cobblemon finishes its normal controls. */
@Mixin(value = BattleGimmickButton.class, remap = false)
public class BattleGimmickButtonMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void cobblemonBattleUi$hideNativeButton(
            PoseStack matrices, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        ci.cancel();
    }
}

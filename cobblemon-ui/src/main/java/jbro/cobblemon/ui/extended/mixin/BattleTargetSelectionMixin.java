package jbro.cobblemon.ui.extended.mixin;

import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleTargetSelection;
import jbro.cobblemon.ui.extended.ui.shared.BattleTargetRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replaces the native target page, while each selected tile still submits through Cobblemon. */
@Mixin(value = BattleTargetSelection.class, remap = false)
public abstract class BattleTargetSelectionMixin {
    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true, remap = true)
    private void cobblemonBattleUi$drawTarget(GuiGraphics context, int mouseX, int mouseY,
            float delta, CallbackInfo ci) {
        BattleTargetSelection selection = (BattleTargetSelection) (Object) this;
        if (!BattleTargetRenderer.supports(selection)) return;
        Minecraft client = Minecraft.getInstance();
        BattleTargetRenderer.drawSelection(context, selection,
                client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight(), mouseX, mouseY);
        ci.cancel();
    }

    @Inject(method = "mousePrimaryClicked", at = @At("HEAD"), cancellable = true)
    private void cobblemonBattleUi$clickTarget(double mouseX, double mouseY,
            CallbackInfoReturnable<Boolean> cir) {
        BattleTargetSelection selection = (BattleTargetSelection) (Object) this;
        if (!BattleTargetRenderer.supports(selection)) return;
        Minecraft client = Minecraft.getInstance();
        cir.setReturnValue(BattleTargetRenderer.click(selection, mouseX, mouseY,
                client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight()));
    }
}

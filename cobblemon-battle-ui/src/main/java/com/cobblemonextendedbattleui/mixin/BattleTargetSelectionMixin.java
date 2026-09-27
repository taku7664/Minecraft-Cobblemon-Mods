package jbro.cobblemon.battleui.extended.mixin;

import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleTargetSelection;
import jbro.cobblemon.battleui.extended.ui.shared.BattleTargetRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replaces the native target page, while each selected tile still submits through Cobblemon. */
@Mixin(value = BattleTargetSelection.class, remap = false)
public abstract class BattleTargetSelectionMixin {
    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true, remap = true)
    private void cobblemonBattleUi$drawTarget(DrawContext context, int mouseX, int mouseY,
            float delta, CallbackInfo ci) {
        BattleTargetSelection selection = (BattleTargetSelection) (Object) this;
        if (!BattleTargetRenderer.supports(selection)) return;
        MinecraftClient client = MinecraftClient.getInstance();
        BattleTargetRenderer.drawSelection(context, selection,
                client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight(), mouseX, mouseY);
        ci.cancel();
    }

    @Inject(method = "mousePrimaryClicked", at = @At("HEAD"), cancellable = true)
    private void cobblemonBattleUi$clickTarget(double mouseX, double mouseY,
            CallbackInfoReturnable<Boolean> cir) {
        BattleTargetSelection selection = (BattleTargetSelection) (Object) this;
        if (!BattleTargetRenderer.supports(selection)) return;
        MinecraftClient client = MinecraftClient.getInstance();
        cir.setReturnValue(BattleTargetRenderer.click(selection, mouseX, mouseY,
                client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight()));
    }
}

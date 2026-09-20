package jbro.cobblemon.battlecam.mixin;

import jbro.cobblemon.battlecam.BattlecamController;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
abstract class InGameHudMixin {
    @Inject(method = "renderMainHud", at = @At("HEAD"), cancellable = true)
    private void betterCobblemonBattlecam$hideSurvivalHud(
        DrawContext context,
        RenderTickCounter tickCounter,
        CallbackInfo callbackInfo
    ) {
        if (BattlecamController.isActive()) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void betterCobblemonBattlecam$hideCrosshair(
        DrawContext context,
        RenderTickCounter tickCounter,
        CallbackInfo callbackInfo
    ) {
        if (BattlecamController.isActive()) {
            callbackInfo.cancel();
        }
    }
}

package jbro.cobblemon.battlecam.mixin;

import jbro.cobblemon.battlecam.BattlecamController;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
    private void betterCobblemonBattlecam$hideHand(
        Camera camera,
        float tickDelta,
        Matrix4f matrix,
        CallbackInfo callbackInfo
    ) {
        if (BattlecamController.isActive()) {
            callbackInfo.cancel();
        }
    }
}

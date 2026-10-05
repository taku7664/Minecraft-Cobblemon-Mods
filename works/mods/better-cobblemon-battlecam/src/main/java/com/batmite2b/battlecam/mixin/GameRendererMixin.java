package com.batmite2b.battlecam.mixin;

import com.batmite2b.battlecam.client.BattleCamClient;
import com.batmite2b.battlecam.client.BattleCameraRig;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value={GameRenderer.class})
public abstract class GameRendererMixin {
    @Inject(method={"getFov(Lnet/minecraft/client/Camera;FZ)D"}, at={@At(value="HEAD")}, cancellable=true)
    private void battlecam$overrideFov(Camera camera, float tickDelta, boolean changingFov, CallbackInfoReturnable<Double> cir) {
        if (!BattleCamClient.STATE.shouldOverrideCamera()) {
            return;
        }
        BattleCameraRig rig = BattleCamClient.STATE.rig;
        cir.setReturnValue((double)rig.getRenderFov());
    }
}

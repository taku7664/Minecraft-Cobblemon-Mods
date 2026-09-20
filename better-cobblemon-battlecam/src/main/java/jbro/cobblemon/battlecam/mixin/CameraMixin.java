package jbro.cobblemon.battlecam.mixin;

import jbro.cobblemon.battlecam.BattlecamController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
abstract class CameraMixin {
    @Shadow
    protected abstract void setPos(Vec3d pos);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "update", at = @At("TAIL"))
    private void betterCobblemonBattlecam$applyPose(
        BlockView area,
        Entity focusedEntity,
        boolean thirdPerson,
        boolean inverseView,
        float tickDelta,
        CallbackInfo callbackInfo
    ) {
        BattlecamController.sampleCamera(MinecraftClient.getInstance()).ifPresent(pose -> {
            setPos(pose.position());
            setRotation(pose.yaw(), pose.pitch());
        });
    }
}

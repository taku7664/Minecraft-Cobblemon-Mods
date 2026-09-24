/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.minecraft.client.render.Camera
 *  net.minecraft.entity.Entity
 *  net.minecraft.world.BlockView
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.Shadow
 *  org.spongepowered.asm.mixin.injection.At
 *  org.spongepowered.asm.mixin.injection.Inject
 *  org.spongepowered.asm.mixin.injection.callback.CallbackInfo
 */
package com.batmite2b.battlecam.mixin;

import com.batmite2b.battlecam.client.BattleCamClient;
import com.batmite2b.battlecam.client.BattleCameraRig;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={Camera.class})
public abstract class CameraMixin {
    @Shadow
    protected abstract void setPos(Vec3d pos);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Inject(method={"update(Lnet/minecraft/world/BlockView;Lnet/minecraft/entity/Entity;ZZF)V"}, at={@At(value="TAIL")})
    private void battlecam$overrideCamera(BlockView area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo ci) {
        if (!BattleCamClient.STATE.shouldOverrideCamera()) {
            return;
        }
        BattleCameraRig rig = BattleCamClient.STATE.rig;
        rig.renderStep();
        this.setPos(rig.getRenderPos());
        this.setRotation(rig.getRenderYaw(), rig.getRenderPitch());
    }
}

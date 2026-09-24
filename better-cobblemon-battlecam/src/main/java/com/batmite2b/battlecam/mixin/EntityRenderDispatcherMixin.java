/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.minecraft.client.MinecraftClient
 *  net.minecraft.client.render.VertexConsumerProvider
 *  net.minecraft.client.render.entity.EntityRenderDispatcher
 *  net.minecraft.client.util.math.MatrixStack
 *  net.minecraft.entity.Entity
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.injection.At
 *  org.spongepowered.asm.mixin.injection.Inject
 *  org.spongepowered.asm.mixin.injection.callback.CallbackInfo
 */
package com.batmite2b.battlecam.mixin;

import com.batmite2b.battlecam.client.BattleCamClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={EntityRenderDispatcher.class})
public abstract class EntityRenderDispatcherMixin {
    @Inject(method={"render(Lnet/minecraft/entity/Entity;DDDFFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V"}, at={@At(value="HEAD")}, cancellable=true)
    private <E extends Entity> void battlecam$hideOwnPlayerBody(E entity, double x, double y, double z, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }
        if (entity == client.player && BattleCamClient.STATE.shouldHideOwnBody()) {
            ci.cancel();
            return;
        }
        if (BattleCamClient.STATE.shouldHideObstructingEntity(entity)) {
            ci.cancel();
        }
    }
}

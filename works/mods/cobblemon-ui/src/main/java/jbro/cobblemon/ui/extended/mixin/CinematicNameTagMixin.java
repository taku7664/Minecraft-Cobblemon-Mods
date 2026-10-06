package jbro.cobblemon.ui.extended.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import jbro.cobblemon.ui.extended.CinematicLetterbox;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Name tags float over a close-up's face; the caption already names the speaker, so they hide during a scene. The
 * drawing itself is skipped, since living entities' renderers decide on showing a name in an override of their own.
 */
@Mixin(EntityRenderer.class)
abstract class CinematicNameTagMixin {
    @Inject(method = "renderNameTag", at = @At("HEAD"), cancellable = true)
    private void cobblemonUi$hideNamesInScene(Entity entity, Component name, PoseStack poseStack, MultiBufferSource buffers,
                                             int light, float partialTick, CallbackInfo ci) {
        if (CinematicLetterbox.isActive()) ci.cancel();
    }
}

package jbro.cobblemon.morebattlecontent.internal.mixin.client;

import jbro.cobblemon.morebattlecontent.client.ShadowTerrainHologramRenderer;
import jbro.cobblemon.morebattlecontent.client.ShadowTrainerProjectionRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Executes after Iris's default-priority RETURN injector finalizes the external shader pipeline. */
@Mixin(value = LevelRenderer.class, priority = 900)
public abstract class LevelRendererLateHologramMixin {
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void mbc$compositeTerrainHologramAfterExternalShader(
        DeltaTracker deltaTracker,
        boolean renderBlockOutline,
        Camera camera,
        GameRenderer gameRenderer,
        LightTexture lightTexture,
        Matrix4f modelViewMatrix,
        Matrix4f projectionMatrix,
        CallbackInfo callbackInfo
    ) {
        ShadowTerrainHologramRenderer.compositeAfterExternalShaderPack();
        ShadowTrainerProjectionRenderer.renderAfterExternalShaderPack();
    }
}

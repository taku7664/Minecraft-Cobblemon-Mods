package jbro.cobblemon.betterbattlepresentation.mixin.client;

import jbro.cobblemon.betterbattlepresentation.client.DynamaxSkyRenderer;
import jbro.cobblemon.betterbattlepresentation.client.DynamaxWorldGradeRenderer;
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

@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Inject(method = "renderSky", at = @At("TAIL"), require = 0)
    private void betterBattlePresentation$afterNormalSky(
        Matrix4f modelViewMatrix,
        Matrix4f projectionMatrix,
        float partialTick,
        Camera camera,
        boolean fogBlocksSky,
        Runnable setupFog,
        CallbackInfo callbackInfo
    ) {
        DynamaxSkyRenderer.render(modelViewMatrix, projectionMatrix);
    }

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void betterBattlePresentation$afterRenderedWorld(
        DeltaTracker deltaTracker,
        boolean renderBlockOutline,
        Camera camera,
        GameRenderer gameRenderer,
        LightTexture lightTexture,
        Matrix4f modelViewMatrix,
        Matrix4f projectionMatrix,
        CallbackInfo callbackInfo
    ) {
        DynamaxWorldGradeRenderer.render();
    }
}

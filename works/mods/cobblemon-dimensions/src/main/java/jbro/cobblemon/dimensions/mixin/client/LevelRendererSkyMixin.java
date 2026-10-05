package jbro.cobblemon.dimensions.mixin.client;

import jbro.cobblemon.dimensions.client.DimensionLook;
import jbro.cobblemon.dimensions.client.SkyExtras;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A dimension's own sun, and whatever else [SkyExtras] draws on its sky. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererSkyMixin {
    @Shadow private ClientLevel level;

    /** The sun is the first texture the sky binds; the moon is the second. */
    @ModifyArg(method = "renderSky", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderTexture(ILnet/minecraft/resources/ResourceLocation;)V", ordinal = 0), index = 1)
    private ResourceLocation cobblemonDimensions$sunTexture(ResourceLocation texture) {
        if (level != null && level.effects() instanceof DimensionLook look && look.getSunTexture() != null) return look.getSunTexture();
        return texture;
    }

    @ModifyConstant(method = "renderSky", constant = @Constant(floatValue = 30.0f))
    private float cobblemonDimensions$sunSize(float size) {
        if (level != null && level.effects() instanceof DimensionLook look) return look.getSunSize();
        return size;
    }

    @Inject(method = "renderSky", at = @At("TAIL"))
    private void cobblemonDimensions$extras(Matrix4f frustum, Matrix4f projection, float partialTick, Camera camera,
                                            boolean foggy, Runnable skyFogSetup, CallbackInfo ci) {
        if (level != null && level.effects() instanceof DimensionLook look) SkyExtras.INSTANCE.render(level, look, frustum, partialTick);
    }
}

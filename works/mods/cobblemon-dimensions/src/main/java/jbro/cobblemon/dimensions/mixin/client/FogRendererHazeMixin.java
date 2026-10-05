package jbro.cobblemon.dimensions.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import jbro.cobblemon.dimensions.client.DimensionLook;
import kotlin.Pair;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A dimension's haze: terrain fog drawn closer than the overworld's. Water, lava and blindness keep their own fog. */
@Mixin(FogRenderer.class)
abstract class FogRendererHazeMixin {
    @Inject(method = "setupFog", at = @At("TAIL"))
    private static void cobblemonDimensions$haze(Camera camera, FogRenderer.FogMode mode, float renderDistance, boolean thickFog,
                                                 float partialTick, CallbackInfo ci) {
        if (mode != FogRenderer.FogMode.FOG_TERRAIN || thickFog || camera.getFluidInCamera() != FogType.NONE) return;
        var level = Minecraft.getInstance().level;
        if (level == null || !(level.effects() instanceof DimensionLook look) || look.getHaze() == null) return;
        if (camera.getEntity() instanceof LivingEntity living
                && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS))) return;
        var biome = level.getBiome(camera.getBlockPosition()).unwrapKey().map(key -> key.location().getPath()).orElse(null);
        Pair<Float, Float> range = look.hazeRange(look.getHaze(), biome, renderDistance);
        RenderSystem.setShaderFogStart(range.getFirst());
        RenderSystem.setShaderFogEnd(range.getSecond());
    }
}

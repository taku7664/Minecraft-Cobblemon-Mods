package jbro.cobblemon.betterbattlepresentation.mixin.client;

import jbro.cobblemon.betterbattlepresentation.client.DynamaxCloudTint;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LevelRenderer.class)
abstract class DynamaxCloudColorMixin {
    @Redirect(
        method = "renderClouds",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getCloudColor(F)Lnet/minecraft/world/phys/Vec3;"
        ),
        require = 0
    )
    private Vec3 betterBattlePresentation$tintDynamaxClouds(ClientLevel level, float partialTick) {
        return DynamaxCloudTint.apply(level.getCloudColor(partialTick));
    }
}

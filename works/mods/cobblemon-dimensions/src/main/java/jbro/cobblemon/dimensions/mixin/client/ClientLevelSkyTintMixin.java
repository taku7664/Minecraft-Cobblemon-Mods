package jbro.cobblemon.dimensions.mixin.client;

import jbro.cobblemon.dimensions.client.DimensionLook;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The sky color comes from the biome; a dimension's look tints it on the way out. */
@Mixin(ClientLevel.class)
abstract class ClientLevelSkyTintMixin {
    @Inject(method = "getSkyColor", at = @At("RETURN"), cancellable = true)
    private void cobblemonDimensions$tintSky(Vec3 pos, float partialTick, CallbackInfoReturnable<Vec3> cir) {
        ClientLevel level = (ClientLevel) (Object) this;
        if (level.effects() instanceof DimensionLook look) {
            float brightness = level.getTimeOfDay(partialTick);
            // Same day curve the sky itself uses: brightest at noon, dark at midnight.
            float day = Math.max(0f, Math.min(1f, (float) Math.cos(brightness * Math.PI * 2) * 2f + 0.5f));
            cir.setReturnValue(look.tintSky(cir.getReturnValue(), day));
        }
    }
}

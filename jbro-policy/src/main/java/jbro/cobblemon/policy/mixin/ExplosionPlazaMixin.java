package jbro.cobblemon.policy.mixin;

import jbro.cobblemon.policy.plaza.PlazaProtection;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Explosions in the plaza break no blocks and start no fire. */
@Mixin(Explosion.class)
abstract class ExplosionPlazaMixin {
    @Shadow @Final private Level level;

    @Inject(method = "finalizeExplosion", at = @At("HEAD"), cancellable = true)
    private void jbroPolicy$keepPlazaBlocks(boolean spawnParticles, CallbackInfo ci) {
        if (PlazaProtection.deniesEnvironment(level)) ci.cancel();
    }
}

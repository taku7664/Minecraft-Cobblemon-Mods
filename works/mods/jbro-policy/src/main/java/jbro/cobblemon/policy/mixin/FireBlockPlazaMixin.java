package jbro.cobblemon.policy.mixin;

import jbro.cobblemon.policy.plaza.PlazaProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fire in the plaza neither spreads nor burns blocks away. */
@Mixin(FireBlock.class)
abstract class FireBlockPlazaMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void jbroPolicy$stopPlazaFire(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (PlazaProtection.deniesEnvironment(level)) ci.cancel();
    }
}

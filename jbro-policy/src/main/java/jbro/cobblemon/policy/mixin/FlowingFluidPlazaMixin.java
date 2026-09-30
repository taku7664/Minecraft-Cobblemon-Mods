package jbro.cobblemon.policy.mixin;

import jbro.cobblemon.policy.plaza.PlazaProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Water and lava in the plaza stay where they are. */
@Mixin(FlowingFluid.class)
abstract class FlowingFluidPlazaMixin {
    @Inject(method = "spread", at = @At("HEAD"), cancellable = true)
    private void jbroPolicy$stopPlazaFlow(Level level, BlockPos pos, FluidState state, CallbackInfo ci) {
        if (PlazaProtection.deniesEnvironment(level)) ci.cancel();
    }
}

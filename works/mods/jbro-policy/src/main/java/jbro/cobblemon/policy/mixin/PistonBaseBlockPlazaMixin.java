package jbro.cobblemon.policy.mixin;

import jbro.cobblemon.policy.plaza.PlazaProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Pistons in the plaza move no blocks. */
@Mixin(PistonBaseBlock.class)
abstract class PistonBaseBlockPlazaMixin {
    @Inject(method = "moveBlocks", at = @At("HEAD"), cancellable = true)
    private void jbroPolicy$stopPlazaPistons(Level level, BlockPos pos, Direction direction, boolean extending,
                                             CallbackInfoReturnable<Boolean> cir) {
        if (PlazaProtection.deniesEnvironment(level)) cir.setReturnValue(false);
    }
}

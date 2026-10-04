package jbro.cobblemon.policy.mixin;

import com.cobblemon.mod.common.block.PokeSnackBlock;
import com.cobblemon.mod.common.block.entity.PokeSnackBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cobblemon drops only the candle of a broken Poke Snack; an unused snack comes back whole. */
@Mixin(PokeSnackBlock.class)
abstract class PokeSnackBlockDropMixin {
    @Inject(method = "playerWillDestroy", at = @At("HEAD"))
    private void jbroPolicy$dropUnusedSnack(Level level, BlockPos pos, BlockState state, Player player,
                                            CallbackInfoReturnable<BlockState> cir) {
        if (level.isClientSide || player.isCreative()) return;
        if (level.getBlockEntity(pos) instanceof PokeSnackBlockEntity snack && snack.getAmountSpawned() == 0) {
            Block.popResource(level, pos, snack.toItemStack());
        }
    }
}

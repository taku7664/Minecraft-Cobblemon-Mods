package jbro.cobblemon.simplemyroom.mixin;

import jbro.cobblemon.simplemyroom.SimpleMyRoom;
import jbro.cobblemon.simplemyroom.room.RoomDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockMixin {
    @Inject(method = "moveBlocks", at = @At("HEAD"), cancellable = true)
    private void simpleMyRoom$disablePistons(
        Level level, BlockPos pos, Direction direction, boolean extending, CallbackInfoReturnable<Boolean> callback
    ) {
        if (SimpleMyRoom.protectionEnabled() && SimpleMyRoom.config().protection.disablePistons && RoomDimensions.isRoom(level)) {
            callback.setReturnValue(false);
        }
    }
}

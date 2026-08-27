package jbro.cobblemon.simplemyroom.mixin;

import jbro.cobblemon.simplemyroom.SimpleMyRoom;
import jbro.cobblemon.simplemyroom.room.RoomAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelMixin {
    @Inject(
        method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void simpleMyRoom$protectUnallocatedSpace(
        BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> callback
    ) {
        if (!SimpleMyRoom.protectionEnabled() || !SimpleMyRoom.config().protection.preventChangesOutsideAllocatedRooms
            || !((Object) this instanceof ServerLevel level)) return;
        if (RoomAccess.isProtectedBoundary(level, pos) && !state.is(RoomAccess.configuredBoundaryBlock())) {
            callback.setReturnValue(false);
            return;
        }
        if (!RoomAccess.isAllocated(level, pos)) callback.setReturnValue(false);
    }
}

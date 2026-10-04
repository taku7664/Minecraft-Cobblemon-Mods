package jbro.cobblemon.simplemyroom.mixin;

import jbro.cobblemon.simplemyroom.SimpleMyRoom;
import jbro.cobblemon.simplemyroom.room.RoomAccess;
import jbro.cobblemon.simplemyroom.room.RoomDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FarmBlock.class)
public abstract class FarmBlockMixin {
    @Inject(method = "turnToDirt", at = @At("HEAD"), cancellable = true)
    private static void simpleMyRoom$preventVisitorTrampling(
        Entity entity, BlockState state, Level level, BlockPos pos, CallbackInfo callback
    ) {
        if (SimpleMyRoom.protectionEnabled() && SimpleMyRoom.config().protection.preventVisitorFarmlandTrample
            && RoomDimensions.isRoom(level)
            && entity instanceof ServerPlayer player
            && !RoomAccess.canModify(player, pos)) {
            callback.cancel();
        }
    }
}

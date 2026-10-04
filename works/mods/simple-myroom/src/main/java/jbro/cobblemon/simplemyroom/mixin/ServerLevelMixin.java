package jbro.cobblemon.simplemyroom.mixin;

import jbro.cobblemon.simplemyroom.SimpleMyRoom;
import jbro.cobblemon.simplemyroom.room.RoomAccess;
import jbro.cobblemon.simplemyroom.room.RoomDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void simpleMyRoom$protectUnallocatedSpace(Entity entity, CallbackInfoReturnable<Boolean> callback) {
        protectEntitySpawn(entity, callback);
    }

    @Inject(method = "tryAddFreshEntityWithPassengers", at = @At("HEAD"), cancellable = true)
    private void simpleMyRoom$protectPassengerSpawn(Entity entity, CallbackInfoReturnable<Boolean> callback) {
        protectEntitySpawn(entity, callback);
    }

    private void protectEntitySpawn(Entity entity, CallbackInfoReturnable<Boolean> callback) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (SimpleMyRoom.protectionEnabled() && SimpleMyRoom.config().protection.preventEntitySpawnsOutsideAllocatedRooms
            && RoomDimensions.isRoom(level)
            && !RoomAccess.isAllocated(level, BlockPos.containing(entity.position()))) {
            callback.setReturnValue(false);
        }
    }
}

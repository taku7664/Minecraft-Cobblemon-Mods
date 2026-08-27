package jbro.cobblemon.simplemyroom.mixin;

import jbro.cobblemon.simplemyroom.SimpleMyRoom;
import jbro.cobblemon.simplemyroom.room.RoomDimensions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BaseFireBlock.class)
public abstract class BaseFireBlockMixin {
    @Inject(method = "inPortalDimension", at = @At("HEAD"), cancellable = true)
    private static void simpleMyRoom$allowNetherPortal(Level level, CallbackInfoReturnable<Boolean> callback) {
        if (SimpleMyRoom.protectionEnabled() && SimpleMyRoom.config().protection.allowNetherPortalCreation && RoomDimensions.isRoom(level)) {
            callback.setReturnValue(true);
        }
    }
}

package jbro.cobblemon.simplemyroom.mixin;

import jbro.cobblemon.simplemyroom.SimpleMyRoom;
import jbro.cobblemon.simplemyroom.room.RoomDimensions;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Explosion.class)
public abstract class ExplosionMixin {
    @Shadow @Final private Level level;

    @Shadow public abstract void clearToBlow();

    @Inject(method = "explode", at = @At("TAIL"))
    private void simpleMyRoom$protectBlocks(CallbackInfo callback) {
        if (SimpleMyRoom.protectionEnabled() && SimpleMyRoom.config().protection.preventExplosionsFromBreakingBlocks && RoomDimensions.isRoom(level)) {
            clearToBlow();
        }
    }
}

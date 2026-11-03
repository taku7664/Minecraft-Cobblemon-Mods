package jbro.cobblemon.battlespeed.mixin;

import com.cobblemon.mod.common.battles.dispatch.WaitDispatch;
import jbro.cobblemon.battlespeed.server.BattleSpeedService;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The fixed pause after a battle message (dispatchWaiting); Mega Showdown's pauses use it too. */
@Mixin(value = WaitDispatch.class, remap = false)
abstract class WaitDispatchMixin {
    @Shadow @Final @Mutable private long readyTime;

    @Inject(method = "<init>(F)V", at = @At("RETURN"))
    private void battleSpeed$shortenWait(float delaySeconds, CallbackInfo callback) {
        double speed = BattleSpeedService.messageWaitSpeed();
        if (speed == 1.0) {
            return;
        }
        long now = System.currentTimeMillis();
        long remaining = readyTime - now;
        if (remaining > 0) {
            readyTime = now + Math.round(remaining / speed);
        }
    }
}

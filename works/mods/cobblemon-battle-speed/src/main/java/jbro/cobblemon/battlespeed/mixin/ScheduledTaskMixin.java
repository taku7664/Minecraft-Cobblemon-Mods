package jbro.cobblemon.battlespeed.mixin;

import com.cobblemon.mod.common.api.scheduling.ScheduledTask;
import jbro.cobblemon.battlespeed.server.BattleSpeedService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every afterOnServer and delayedFuture becomes a ScheduledTask. Only the first delay is shortened, and only for
 * tasks created inside a battle scope; repeating intervals and tasks outside battles are left alone. The Kotlin
 * default-argument constructor delegates to this one, so it is scaled once.
 */
@Mixin(value = ScheduledTask.class, remap = false)
abstract class ScheduledTaskMixin {
    @Shadow private float secondsRemaining;

    @Inject(method = "<init>(Lkotlin/jvm/functions/Function1;Ljava/lang/String;FFI)V", at = @At("RETURN"))
    private void battleSpeed$shortenDelay(CallbackInfo callback) {
        if (secondsRemaining <= 0F) {
            return;
        }
        double speed = BattleSpeedService.scheduledTaskSpeed();
        if (speed != 1.0) {
            secondsRemaining = (float) (secondsRemaining / speed);
        }
    }
}

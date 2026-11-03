package jbro.cobblemon.battlespeed.mixin.client;

import com.cobblemon.mod.common.client.battle.animations.HealthChangeAnimation;
import jbro.cobblemon.battlespeed.client.ClientBattleSpeedState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** The HP bar drains over a fixed second; at higher speeds it has to drain faster to keep up. */
@Mixin(value = HealthChangeAnimation.class, remap = false)
abstract class HealthChangeAnimationMixin {
    @ModifyVariable(method = "invoke", at = @At("HEAD"), argsOnly = true)
    private float battleSpeed$speedUp(float deltaTicks) {
        return deltaTicks * ClientBattleSpeedState.currentSpeed();
    }
}

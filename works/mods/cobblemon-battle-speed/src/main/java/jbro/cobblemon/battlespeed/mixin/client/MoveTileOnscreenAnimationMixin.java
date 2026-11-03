package jbro.cobblemon.battlespeed.mixin.client;

import com.cobblemon.mod.common.client.battle.animations.MoveTileOnscreenAnimation;
import jbro.cobblemon.battlespeed.client.ClientBattleSpeedState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** The HUD tile sliding in after a switch. */
@Mixin(value = MoveTileOnscreenAnimation.class, remap = false)
abstract class MoveTileOnscreenAnimationMixin {
    @ModifyVariable(method = "invoke", at = @At("HEAD"), argsOnly = true)
    private float battleSpeed$speedUp(float deltaTicks) {
        return deltaTicks * ClientBattleSpeedState.currentSpeed();
    }
}

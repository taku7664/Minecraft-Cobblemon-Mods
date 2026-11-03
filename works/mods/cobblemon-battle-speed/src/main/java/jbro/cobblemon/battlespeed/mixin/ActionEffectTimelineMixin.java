package jbro.cobblemon.battlespeed.mixin;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.moves.animations.ActionEffectContext;
import com.cobblemon.mod.common.api.moves.animations.ActionEffectTimeline;
import com.cobblemon.mod.common.api.moves.animations.keyframes.ActionEffectKeyframe;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import jbro.cobblemon.battlespeed.core.SpeedScope;
import jbro.cobblemon.battlespeed.server.BattleSpeedService;
import kotlin.Unit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Iterator;
import java.util.concurrent.CompletableFuture;

/**
 * Move animation keyframes after the first one start from Cobblemon's task tracker, outside the battle tick. Each
 * keyframe is started by {@code chainKeyframes}, whose context names the battle, so the scope is opened there.
 */
@Mixin(value = ActionEffectTimeline.class, remap = false)
abstract class ActionEffectTimelineMixin {
    @Inject(method = "run", at = @At("HEAD"), cancellable = true)
    private void battleSpeed$skipAtHighSpeed(
        ActionEffectContext context,
        CallbackInfoReturnable<CompletableFuture<Unit>> callback
    ) {
        var battle = battleSpeed$battleOf(context);
        if (battle != null && BattleSpeedService.skipMoveEffects(battle)) {
            callback.setReturnValue(CompletableFuture.completedFuture(Unit.INSTANCE));
        }
    }

    @WrapMethod(method = "chainKeyframes")
    private void battleSpeed$markKeyframe(
        ActionEffectContext context,
        Iterator<ActionEffectKeyframe> iterator,
        CompletableFuture<Unit> finalFuture,
        Operation<Void> original
    ) {
        var battle = battleSpeed$battleOf(context);
        if (battle == null) {
            original.call(context, iterator, finalFuture);
            return;
        }
        SpeedScope.push(battle, SpeedScope.Kind.MOVE_EFFECT);
        try {
            original.call(context, iterator, finalFuture);
        } finally {
            SpeedScope.pop();
        }
    }

    private static PokemonBattle battleSpeed$battleOf(ActionEffectContext context) {
        for (Object provider : context.getProviders()) {
            if (provider instanceof PokemonBattle battle) {
                return battle;
            }
        }
        return null;
    }
}

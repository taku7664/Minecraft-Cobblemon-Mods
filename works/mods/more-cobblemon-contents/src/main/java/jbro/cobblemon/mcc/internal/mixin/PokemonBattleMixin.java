package jbro.cobblemon.mcc.internal.mixin;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.battles.BattleFormat;
import com.cobblemon.mod.common.battles.BattleSide;
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173BattleRuleHooks;
import jbro.cobblemon.mcc.internal.battle.BattleEntryHold;
import jbro.cobblemon.mcc.internal.battle.ManagedTurnInterceptors;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PokemonBattle.class)
abstract class PokemonBattleMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void mcc$attachPendingTowerRules(
        BattleFormat format,
        BattleSide side1,
        BattleSide side2,
        CallbackInfo callbackInfo
    ) {
        Cobblemon173BattleRuleHooks.attachConstructed((PokemonBattle) (Object) this);
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void mcc$startHeldBattleBeforeStop(CallbackInfo callbackInfo) {
        BattleEntryHold.beforeStop((PokemonBattle) (Object) this);
    }

    @Inject(method = "end", at = @At("HEAD"))
    private void mcc$hideManagedMechanicPolicy(CallbackInfo callbackInfo) {
        Cobblemon173BattleRuleHooks.beforeBattleEnd((PokemonBattle) (Object) this);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void mcc$observeTurnRequests(CallbackInfo callbackInfo) {
        ManagedTurnInterceptors.observe((PokemonBattle) (Object) this);
    }
}

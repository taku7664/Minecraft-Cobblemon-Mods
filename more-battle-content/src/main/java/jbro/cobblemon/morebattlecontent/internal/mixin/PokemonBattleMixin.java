package jbro.cobblemon.morebattlecontent.internal.mixin;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.battles.BattleFormat;
import com.cobblemon.mod.common.battles.BattleSide;
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.Cobblemon173BattleRuleHooks;
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpPlayNetworking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PokemonBattle.class)
abstract class PokemonBattleMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void mbc$attachPendingTowerRules(
        BattleFormat format,
        BattleSide side1,
        BattleSide side2,
        CallbackInfo callbackInfo
    ) {
        Cobblemon173BattleRuleHooks.attachConstructed((PokemonBattle) (Object) this);
    }

    @Inject(method = "end", at = @At("HEAD"))
    private void mbc$hideManagedMechanicPolicy(CallbackInfo callbackInfo) {
        PokemonBattle battle = (PokemonBattle) (Object) this;
        PvpPlayNetworking.forgetBattleTurn(battle.getBattleId());
        Cobblemon173BattleRuleHooks.hideClientMechanicPolicy(battle);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void mbc$observePvpTurnRequests(CallbackInfo callbackInfo) {
        PvpPlayNetworking.observeBattleTurn((PokemonBattle) (Object) this);
    }
}

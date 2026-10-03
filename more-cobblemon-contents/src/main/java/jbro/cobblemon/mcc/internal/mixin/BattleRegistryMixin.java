package jbro.cobblemon.mcc.internal.mixin;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.battles.BattleRegistry;
import jbro.cobblemon.mcc.internal.battle.BattleEntryHold;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Holds an approved battle's Showdown start for the entry transition; see {@link BattleEntryHold}. */
@Mixin(value = BattleRegistry.class, remap = false)
abstract class BattleRegistryMixin {
    @Inject(method = "startShowdown", at = @At("HEAD"), cancellable = true)
    private void mcc$holdForBattleEntry(PokemonBattle battle, CallbackInfo callbackInfo) {
        if (BattleEntryHold.intercept(battle)) {
            callbackInfo.cancel();
        }
    }
}

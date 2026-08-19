package jbro.cobblemon.morebattlecontent.internal.mixin;

import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.Cobblemon173BattleRuleHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerBattleActor.class)
abstract class PlayerBattleActorMixin {
    @Inject(method = "awardExperience", at = @At("HEAD"), cancellable = true)
    private void mbc$suppressManagedBattleExperience(
        BattlePokemon battlePokemon,
        int amount,
        CallbackInfo callbackInfo
    ) {
        PlayerBattleActor actor = (PlayerBattleActor) (Object) this;
        if (Cobblemon173BattleRuleHooks.shouldSuppressExperience(actor.getBattle().getBattleId())) {
            callbackInfo.cancel();
        }
    }
}

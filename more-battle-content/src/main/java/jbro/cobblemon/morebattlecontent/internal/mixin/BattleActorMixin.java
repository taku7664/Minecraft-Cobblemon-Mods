package jbro.cobblemon.morebattlecontent.internal.mixin;

import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.ShowdownActionResponse;
import com.cobblemon.mod.common.exception.IllegalActionChoiceException;
import java.util.List;
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.Cobblemon173BattleRuleHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BattleActor.class)
abstract class BattleActorMixin {
    @Inject(method = "setActionResponses", at = @At("HEAD"))
    private void mbc$validateTowerRules(
        List<? extends ShowdownActionResponse> responses,
        CallbackInfo callbackInfo
    ) {
        BattleActor actor = (BattleActor) (Object) this;
        String rejection = Cobblemon173BattleRuleHooks.rejectionMessage(actor, responses);
        if (rejection != null) {
            throw new IllegalActionChoiceException(actor, rejection);
        }
    }

    @Inject(
        method = "setActionResponses",
        at = @At(
            value = "INVOKE",
            target = "Lcom/cobblemon/mod/common/api/battles/model/PokemonBattle;checkForInputDispatch()V"
        )
    )
    private void mbc$recordAcceptedTowerMechanic(
        List<? extends ShowdownActionResponse> responses,
        CallbackInfo callbackInfo
    ) {
        Cobblemon173BattleRuleHooks.recordAccepted((BattleActor) (Object) this, responses);
    }
}

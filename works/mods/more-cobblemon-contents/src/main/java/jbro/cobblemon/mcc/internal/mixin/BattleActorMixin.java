package jbro.cobblemon.mcc.internal.mixin;

import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.ShowdownActionResponse;
import com.cobblemon.mod.common.exception.IllegalActionChoiceException;
import java.util.ArrayList;
import java.util.List;
import jbro.cobblemon.mcc.internal.battle.ManagedTurnFailureRecovery;
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173BattleRuleHooks;
import jbro.cobblemon.mcc.internal.battle.ManagedTurnCapture;
import jbro.cobblemon.mcc.internal.battle.ManagedTurnInterceptors;
import jbro.cobblemon.mcc.internal.battle.ManagedTurnResponseCardinality;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BattleActor.class)
abstract class BattleActorMixin {
    @Unique
    private boolean mcc$validatingManagedTurn;
    @Unique
    private boolean mcc$managedTurnValidated;
    @Unique
    private boolean mcc$managedTurnCompleted;
    @Unique
    private ManagedTurnCapture mcc$turnCapture;

    @Inject(method = "setActionResponses", at = @At("HEAD"), cancellable = true)
    private void mcc$validateManagedRules(
        List<? extends ShowdownActionResponse> responses,
        CallbackInfo callbackInfo
    ) {
        if (mcc$validatingManagedTurn) {
            return;
        }
        BattleActor actor = (BattleActor) (Object) this;
        Object originalRequest = actor.getRequest();
        if (originalRequest == null) {
            return;
        }
        String rejection = Cobblemon173BattleRuleHooks.rejectionMessage(actor, responses);
        if (rejection != null) {
            throw new IllegalActionChoiceException(actor, rejection);
        }
        ManagedTurnCapture capture = ManagedTurnInterceptors.capture(actor);
        boolean managedRules = Cobblemon173BattleRuleHooks.isRegisteredBattle(actor.getBattle().getBattleId());
        if (capture == null && !managedRules) {
            return;
        }
        if (capture != null && capture.getTimedOut()) {
            capture.resolveTimedOut(actor);
            callbackInfo.cancel();
            return;
        }
        int activeChoices = actor.getRequest().getActive() == null ? 0 : actor.getRequest().getActive().size();
        int forcedSwitchChoices = actor.getRequest().getForceSwitch().size();
        if (!ManagedTurnResponseCardinality.accepts(activeChoices, forcedSwitchChoices, responses.size())) {
            if (capture != null) {
                capture.reject();
            }
            throw new IllegalActionChoiceException(actor, "Managed action response count does not match the request");
        }
        List<ShowdownActionResponse> originalResponses;
        List<ShowdownActionResponse> submittedResponses;
        try {
            originalResponses = new ArrayList<>(actor.getResponses());
            submittedResponses = new ArrayList<>(responses);
        } catch (RuntimeException | LinkageError failure) {
            if (capture != null) {
                ManagedTurnFailureRecovery.releaseReservation(
                    failure,
                    capture::reject
                );
            }
            throw failure;
        }
        mcc$validatingManagedTurn = true;
        mcc$managedTurnValidated = false;
        mcc$managedTurnCompleted = false;
        mcc$turnCapture = capture;
        try {
            actor.setActionResponses(responses);
            mcc$completeManagedTurn(actor, capture, originalRequest, originalResponses, submittedResponses);
        } catch (RuntimeException | LinkageError failure) {
            mcc$failManagedTurn(actor, capture, originalRequest, originalResponses, failure);
            throw failure;
        } finally {
            mcc$turnCapture = null;
            mcc$managedTurnCompleted = false;
            mcc$managedTurnValidated = false;
            mcc$validatingManagedTurn = false;
        }
        callbackInfo.cancel();
    }

    @Inject(
        method = "setActionResponses",
        at = @At(
            value = "INVOKE",
            target = "Lcom/cobblemon/mod/common/api/battles/model/PokemonBattle;checkForInputDispatch()V"
        )
    )
    private void mcc$recordAcceptedTowerMechanic(
        List<? extends ShowdownActionResponse> responses,
        CallbackInfo callbackInfo
    ) {
        if (mcc$validatingManagedTurn) {
            mcc$managedTurnValidated = true;
        } else {
            Cobblemon173BattleRuleHooks.recordAccepted((BattleActor) (Object) this, responses);
        }
    }

    @Unique
    private void mcc$failManagedTurn(
        BattleActor actor,
        ManagedTurnCapture capture,
        Object originalRequest,
        List<ShowdownActionResponse> originalResponses,
        Throwable failure
    ) {
        if (mcc$managedTurnCompleted) {
            return;
        }
        mcc$managedTurnCompleted = true;
        boolean canRestoreResponses = actor.getRequest() == originalRequest && actor.getMustChoose();
        ManagedTurnFailureRecovery.recover(
            failure,
            canRestoreResponses,
            () -> {
                if (capture != null) {
                    capture.reject();
                }
            },
            () -> {
                actor.getResponses().clear();
                actor.getResponses().addAll(originalResponses);
            },
            () -> Cobblemon173BattleRuleHooks.abortFailedBattle(actor.getBattle().getBattleId())
        );
    }

    @Unique
    private void mcc$completeManagedTurn(
        BattleActor actor,
        ManagedTurnCapture capture,
        Object originalRequest,
        List<ShowdownActionResponse> originalResponses,
        List<ShowdownActionResponse> submittedResponses
    ) {
        if (mcc$managedTurnCompleted) {
            return;
        }
        boolean sameRequestReopened = actor.getMustChoose() && actor.getRequest() == originalRequest;
        if (mcc$managedTurnValidated && !sameRequestReopened) {
            Cobblemon173BattleRuleHooks.recordAccepted(actor, submittedResponses);
            if (capture != null) {
                capture.accept();
            }
        } else {
            if (capture != null) {
                capture.reject();
            }
            actor.getResponses().clear();
            actor.getResponses().addAll(originalResponses);
        }
        mcc$managedTurnCompleted = true;
    }
}

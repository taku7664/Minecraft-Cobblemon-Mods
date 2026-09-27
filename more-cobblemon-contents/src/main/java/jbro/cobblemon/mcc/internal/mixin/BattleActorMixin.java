package jbro.cobblemon.mcc.internal.mixin;

import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.ShowdownActionResponse;
import com.cobblemon.mod.common.exception.IllegalActionChoiceException;
import java.util.ArrayList;
import java.util.List;
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173BattleRuleHooks;
import jbro.cobblemon.mcc.internal.pvp.PvpTurnCapture;
import jbro.cobblemon.mcc.internal.pvp.PvpTurnResponseCardinality;
import jbro.cobblemon.mcc.internal.pvp.network.PvpPlayNetworking;
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
    private PvpTurnCapture mcc$pvpTurnCapture;

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
        PvpTurnCapture capture = PvpPlayNetworking.captureBattleTurn(actor);
        boolean managedRules = Cobblemon173BattleRuleHooks.isRegisteredBattle(actor.getBattle().getBattleId());
        if (capture == null && !managedRules) {
            return;
        }
        if (capture != null && capture.getTimedOut()) {
            PvpPlayNetworking.resolveTimedOutBattleTurn(actor, capture);
            callbackInfo.cancel();
            return;
        }
        int activeChoices = actor.getRequest().getActive() == null ? 0 : actor.getRequest().getActive().size();
        int forcedSwitchChoices = actor.getRequest().getForceSwitch().size();
        if (!PvpTurnResponseCardinality.accepts(activeChoices, forcedSwitchChoices, responses.size())) {
            if (capture != null) {
                PvpPlayNetworking.rejectBattleTurn(capture);
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
                    () -> PvpPlayNetworking.rejectBattleTurn(capture)
                );
            }
            throw failure;
        }
        mcc$validatingManagedTurn = true;
        mcc$managedTurnValidated = false;
        mcc$managedTurnCompleted = false;
        mcc$pvpTurnCapture = capture;
        try {
            actor.setActionResponses(responses);
            mcc$completeManagedTurn(actor, capture, originalRequest, originalResponses, submittedResponses);
        } catch (RuntimeException | LinkageError failure) {
            mcc$failManagedTurn(actor, capture, originalRequest, originalResponses, failure);
            throw failure;
        } finally {
            mcc$pvpTurnCapture = null;
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
        PvpTurnCapture capture,
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
                    PvpPlayNetworking.rejectBattleTurn(capture);
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
        PvpTurnCapture capture,
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
                PvpPlayNetworking.acceptBattleTurn(capture);
            }
        } else {
            if (capture != null) {
                PvpPlayNetworking.rejectBattleTurn(capture);
            }
            actor.getResponses().clear();
            actor.getResponses().addAll(originalResponses);
        }
        mcc$managedTurnCompleted = true;
    }
}

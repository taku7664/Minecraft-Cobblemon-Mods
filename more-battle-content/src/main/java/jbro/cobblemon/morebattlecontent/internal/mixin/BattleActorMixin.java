package jbro.cobblemon.morebattlecontent.internal.mixin;

import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.ShowdownActionResponse;
import com.cobblemon.mod.common.exception.IllegalActionChoiceException;
import java.util.ArrayList;
import java.util.List;
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.Cobblemon173BattleRuleHooks;
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpTurnCapture;
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpTurnResponseCardinality;
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpPlayNetworking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BattleActor.class)
abstract class BattleActorMixin {
    @Unique
    private boolean mbc$validatingManagedTurn;
    @Unique
    private boolean mbc$managedTurnValidated;
    @Unique
    private boolean mbc$managedTurnCompleted;
    @Unique
    private PvpTurnCapture mbc$pvpTurnCapture;

    @Inject(method = "setActionResponses", at = @At("HEAD"), cancellable = true)
    private void mbc$validateManagedRules(
        List<? extends ShowdownActionResponse> responses,
        CallbackInfo callbackInfo
    ) {
        if (mbc$validatingManagedTurn) {
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
        mbc$validatingManagedTurn = true;
        mbc$managedTurnValidated = false;
        mbc$managedTurnCompleted = false;
        mbc$pvpTurnCapture = capture;
        try {
            actor.setActionResponses(responses);
            mbc$completeManagedTurn(actor, capture, originalRequest, originalResponses, submittedResponses);
        } catch (RuntimeException | LinkageError failure) {
            mbc$failManagedTurn(actor, capture, originalRequest, originalResponses, failure);
            throw failure;
        } finally {
            mbc$pvpTurnCapture = null;
            mbc$managedTurnCompleted = false;
            mbc$managedTurnValidated = false;
            mbc$validatingManagedTurn = false;
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
    private void mbc$recordAcceptedTowerMechanic(
        List<? extends ShowdownActionResponse> responses,
        CallbackInfo callbackInfo
    ) {
        if (mbc$validatingManagedTurn) {
            mbc$managedTurnValidated = true;
        } else {
            Cobblemon173BattleRuleHooks.recordAccepted((BattleActor) (Object) this, responses);
        }
    }

    @Unique
    private void mbc$failManagedTurn(
        BattleActor actor,
        PvpTurnCapture capture,
        Object originalRequest,
        List<ShowdownActionResponse> originalResponses,
        Throwable failure
    ) {
        if (mbc$managedTurnCompleted) {
            return;
        }
        mbc$managedTurnCompleted = true;
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
    private void mbc$completeManagedTurn(
        BattleActor actor,
        PvpTurnCapture capture,
        Object originalRequest,
        List<ShowdownActionResponse> originalResponses,
        List<ShowdownActionResponse> submittedResponses
    ) {
        if (mbc$managedTurnCompleted) {
            return;
        }
        boolean sameRequestReopened = actor.getMustChoose() && actor.getRequest() == originalRequest;
        if (mbc$managedTurnValidated && !sameRequestReopened) {
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
        mbc$managedTurnCompleted = true;
    }
}

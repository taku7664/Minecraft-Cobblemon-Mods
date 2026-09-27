package jbro.cobblemon.mcc.internal.bp

import java.util.UUID
import jbro.cobblemon.mcc.internal.application.BattleContentId

data class BattlePointRewardSettlement(
    val settlementId: UUID,
    val playerId: UUID,
    val contentId: BattleContentId,
    val amount: Long,
    val reason: String,
) {
    init {
        require(amount > 0) { "Battle Point reward amount must be positive" }
        require(reason.isNotBlank()) { "Battle Point reward reason must not be blank" }
    }
}

class BattlePointRewardSettlementService(
    private val applyRequest: (BattlePointRequest) -> BattlePointApplyResult,
) {
    fun settleVictory(
        battleId: UUID,
        playerId: UUID,
        contentId: BattleContentId,
    ): BattlePointApplyResult = settle(
        BattlePointRewardSettlement(
            settlementId = battleId,
            playerId = playerId,
            contentId = contentId,
            amount = STANDARD_VICTORY_REWARD,
            reason = "${contentId.value.substringAfter(':')}_win",
        ),
    )

    fun settle(settlement: BattlePointRewardSettlement): BattlePointApplyResult = applyRequest(
        BattlePointRequest(
            transactionId = settlement.settlementId,
            playerId = settlement.playerId,
            operation = BattlePointOperation.ContentReward(settlement.amount),
            sourceId = BattlePointSourceId(settlement.contentId.value),
            reason = settlement.reason,
        ),
    )

    companion object {
        const val STANDARD_VICTORY_REWARD = 2L
    }
}

fun BattlePointApplyResult.requireAcceptedReward(): BattlePointApplyResult = also {
    check(status == BattlePointApplyStatus.APPLIED || status == BattlePointApplyStatus.ALREADY_APPLIED) {
        "Battle Point reward settlement was rejected: $status"
    }
}

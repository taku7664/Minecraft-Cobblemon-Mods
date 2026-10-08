package jbro.cobblemon.mcc.internal.factory

import java.util.Collections
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import java.util.UUID
import jbro.cobblemon.mcc.internal.record.BattleRecordCategory
import jbro.cobblemon.mcc.internal.record.BattleRecordCompletion
import jbro.cobblemon.mcc.internal.record.BattleRecordKey
import jbro.cobblemon.mcc.internal.record.BattleRecordMetrics
import jbro.cobblemon.mcc.internal.record.BattleRecordOutcome
import jbro.cobblemon.mcc.internal.record.BattleRecordStats

internal fun interface FactoryBattleRecordSink {
    fun record(completion: BattleRecordCompletion): BattleRecordStats
}

internal class FactoryBattleRecordService(private val sink: FactoryBattleRecordSink) {
    fun record(
        playerId: UUID,
        format: FactoryBattleFormat,
        levelMode: FactoryLevelMode,
        outcome: BattleRecordOutcome,
        completedWins: Int,
    ): BattleRecordStats {
        require(completedWins >= 0) { "Factory completed wins must be non-negative" }
        val currentWins = if (outcome == BattleRecordOutcome.WIN) completedWins.toLong() else 0L
        return sink.record(
            BattleRecordCompletion(
                key = BattleRecordKey(
                    playerId,
                    BattleRecordCategory(FactoryRecordContract.CONTENT_ID, format.recordId(levelMode)),
                ),
                outcome = outcome,
                progressMetrics = mapOf(BattleRecordMetrics.CURRENT_FLOOR to currentWins),
                bestMetrics = mapOf(BattleRecordMetrics.HIGHEST_FLOOR to completedWins.toLong()),
            ),
        )
    }

}

internal object FactoryRecordContract {
    const val CONTENT_ID = ManagedBattleContentIds.BATTLE_FACTORY
}

internal fun interface FactoryBattleVictoryRewardSink {
    fun reward(playerId: UUID, battleId: UUID, bp: Long)
}

private val NoopFactoryBattleVictoryRewardSink = FactoryBattleVictoryRewardSink { _, _, _ -> }

internal sealed interface FactoryBattleCompletionResult {
    class Victory(offers: Collection<FactorySwapOffer>, val rewardBp: Long) : FactoryBattleCompletionResult {
        val offers: List<FactorySwapOffer> = Collections.unmodifiableList(ArrayList(offers))
    }
    data object Loss : FactoryBattleCompletionResult
    data object Cancelled : FactoryBattleCompletionResult
    data object NoActiveBattle : FactoryBattleCompletionResult
    data class StaleBattle(val activeBattleId: UUID) : FactoryBattleCompletionResult
}

internal class FactoryBattleCompletionService(
    private val records: FactoryBattleRecordService,
    private val victoryRewards: FactoryBattleVictoryRewardSink = NoopFactoryBattleVictoryRewardSink,
) {
    fun completeVictory(
        playerId: UUID,
        session: FactoryRunSession,
        battleId: UUID,
        opponentSets: Map<UUID, FactoryRentalSet>,
        observations: Map<String, FactoryOpponentObservation>,
    ): FactoryBattleCompletionResult = synchronized(session) {
        checkActive(session, battleId)?.let { return it }
        var rewardBp = 0L
        val offers = session.recordVictory(battleId, opponentSets, observations) { winsAfter ->
            rewardBp = FactoryProgression.victoryRewardBp(winsAfter)
            victoryRewards.reward(playerId, battleId, rewardBp)
            records.record(playerId, session.team.format, session.levelMode, BattleRecordOutcome.WIN, winsAfter)
        }
        FactoryBattleCompletionResult.Victory(offers, rewardBp)
    }

    fun completeLoss(
        playerId: UUID,
        session: FactoryRunSession,
        battleId: UUID,
    ): FactoryBattleCompletionResult = synchronized(session) {
        checkActive(session, battleId)?.let { return it }
        session.recordLoss(battleId) { completedWins ->
            records.record(playerId, session.team.format, session.levelMode, BattleRecordOutcome.LOSS, completedWins)
        }
        FactoryBattleCompletionResult.Loss
    }

    fun cancel(session: FactoryRunSession, battleId: UUID): FactoryBattleCompletionResult = synchronized(session) {
        checkActive(session, battleId)?.let { return it }
        session.cancelBattle(battleId)
        FactoryBattleCompletionResult.Cancelled
    }

    private fun checkActive(
        session: FactoryRunSession,
        battleId: UUID,
    ): FactoryBattleCompletionResult? {
        val active = session.activeBattleId ?: return FactoryBattleCompletionResult.NoActiveBattle
        return if (active == battleId) null else FactoryBattleCompletionResult.StaleBattle(active)
    }
}

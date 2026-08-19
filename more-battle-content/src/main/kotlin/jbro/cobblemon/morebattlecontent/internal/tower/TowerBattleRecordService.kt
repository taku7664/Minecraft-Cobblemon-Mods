package jbro.cobblemon.morebattlecontent.internal.tower

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordCategory
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordCompletion
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordKey
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordMetrics
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordOutcome
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordStats

internal fun interface TowerBattleRecordSink {
    fun record(completion: BattleRecordCompletion): BattleRecordStats
}

internal class TowerBattleRecordService(private val sink: TowerBattleRecordSink) {
    fun record(playerId: UUID, update: TowerProgressUpdate): BattleRecordStats {
        val after = update.after
        return sink.record(
            BattleRecordCompletion(
                key = BattleRecordKey(
                    playerId = playerId,
                    category = BattleRecordCategory(
                        contentId = TowerRecordContract.CONTENT_ID,
                        formatId = after.format.recordId,
                    ),
                ),
                outcome = when (update.outcome) {
                    TowerBattleOutcome.WIN -> BattleRecordOutcome.WIN
                    TowerBattleOutcome.LOSS -> BattleRecordOutcome.LOSS
                },
                progressMetrics = mapOf(
                    BattleRecordMetrics.CURRENT_RANK to after.rank.leaderboardOrder,
                    BattleRecordMetrics.RANK_PROGRESS to after.rankPoints.toLong(),
                    BattleRecordMetrics.MASTER_CYCLE_WINS to after.masterCycleWins.toLong(),
                ),
                bestMetrics = mapOf(
                    BattleRecordMetrics.HIGHEST_RANK to after.rank.leaderboardOrder,
                ),
            ),
        )
    }
}

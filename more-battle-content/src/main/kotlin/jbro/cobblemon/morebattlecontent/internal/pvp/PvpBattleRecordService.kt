package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordCategory
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordCompletion
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordKey
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordOutcome
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordStats

internal fun interface PvpBattleRecordSink {
    fun record(completions: List<BattleRecordCompletion>): List<BattleRecordStats>
}

internal class PvpBattleRecordService(
    private val sink: PvpBattleRecordSink,
) {
    fun recordResult(
        winnerId: UUID,
        loserId: UUID,
        format: PvpBattleFormat,
    ): Map<UUID, BattleRecordStats> {
        require(winnerId != loserId) { "A PvP winner and loser must be different players" }
        val category = BattleRecordCategory(CONTENT_ID, format.recordId)
        val result = sink.record(
            listOf(
                BattleRecordCompletion(BattleRecordKey(winnerId, category), BattleRecordOutcome.WIN),
                BattleRecordCompletion(BattleRecordKey(loserId, category), BattleRecordOutcome.LOSS),
            ),
        )
        require(result.size == 2) { "PvP record sink must return both participant records" }
        return result.associateBy { it.key.playerId }
    }

    companion object {
        const val CONTENT_ID = "pvp"
    }
}

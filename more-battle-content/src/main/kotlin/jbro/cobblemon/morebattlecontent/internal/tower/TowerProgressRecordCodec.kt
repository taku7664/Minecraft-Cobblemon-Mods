package jbro.cobblemon.morebattlecontent.internal.tower

import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordMetricId
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordMetrics
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordStats

internal object TowerRecordContract {
    const val CONTENT_ID = "battle_tower"
}

internal object TowerProgressRecordCodec {
    private val progressMetricIds = listOf(
        BattleRecordMetrics.CURRENT_RANK,
        BattleRecordMetrics.RANK_PROGRESS,
        BattleRecordMetrics.MASTER_CYCLE_WINS,
    )

    fun decode(stats: BattleRecordStats): TowerProgress {
        require(stats.key.category.contentId == TowerRecordContract.CONTENT_ID) {
            "Not a Battle Tower record: ${stats.key.category.contentId}"
        }
        val format = TowerBattleFormat.entries.singleOrNull {
            it.recordId == stats.key.category.formatId
        } ?: throw IllegalArgumentException("Unsupported Battle Tower format: ${stats.key.category.formatId}")

        val present = progressMetricIds.filter(stats.progressMetrics::containsKey)
        if (present.isEmpty()) return TowerProgress.initial(format)
        require(present.size == progressMetricIds.size) {
            "Battle Tower progress metrics must be all present or all absent"
        }

        val rankOrder = stats.progressMetrics.required(BattleRecordMetrics.CURRENT_RANK)
        val rank = TowerRank.entries.singleOrNull { it.leaderboardOrder == rankOrder }
            ?: throw IllegalArgumentException("Invalid Battle Tower rank order: $rankOrder")
        return TowerProgress(
            format = format,
            rank = rank,
            rankPoints = stats.progressMetrics.requiredInt(BattleRecordMetrics.RANK_PROGRESS),
            masterCycleWins = stats.progressMetrics.requiredInt(BattleRecordMetrics.MASTER_CYCLE_WINS),
        )
    }
}

private fun Map<BattleRecordMetricId, Long>.required(metricId: BattleRecordMetricId): Long =
    requireNotNull(this[metricId]) { "Missing Battle Tower progress metric: ${metricId.value}" }

private fun Map<BattleRecordMetricId, Long>.requiredInt(metricId: BattleRecordMetricId): Int {
    val value = required(metricId)
    require(value in 0..Int.MAX_VALUE.toLong()) {
        "Battle Tower progress metric ${metricId.value} is outside the supported range"
    }
    return value.toInt()
}

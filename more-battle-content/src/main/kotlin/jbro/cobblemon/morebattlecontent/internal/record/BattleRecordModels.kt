package jbro.cobblemon.morebattlecontent.internal.record

import java.util.UUID

internal data class BattleRecordCategory(
    val contentId: String,
    val formatId: String,
) {
    init {
        require(RECORD_ID.matches(contentId)) { "Invalid record content ID: $contentId" }
        require(RECORD_ID.matches(formatId)) { "Invalid record format ID: $formatId" }
    }
}

@JvmInline
internal value class BattleRecordMetricId(val value: String) {
    init {
        require(RECORD_ID.matches(value)) { "Invalid record metric ID: $value" }
    }
}

internal data class BattleRecordKey(
    val playerId: UUID,
    val category: BattleRecordCategory,
)

internal enum class BattleRecordOutcome { WIN, LOSS }

internal data class BattleRecordCompletion(
    val key: BattleRecordKey,
    val outcome: BattleRecordOutcome,
    val progressMetrics: Map<BattleRecordMetricId, Long> = emptyMap(),
    val bestMetrics: Map<BattleRecordMetricId, Long> = emptyMap(),
) {
    init {
        require(progressMetrics.values.all { it >= 0 }) { "Progress metrics must be non-negative" }
        require(bestMetrics.values.all { it >= 0 }) { "Best metrics must be non-negative" }
    }
}

internal object BattleRecordMetrics {
    val CURRENT_RANK = BattleRecordMetricId("current_rank")
    val RANK_PROGRESS = BattleRecordMetricId("rank_progress")
    val MASTER_CYCLE_WINS = BattleRecordMetricId("master_cycle_wins")
    val HIGHEST_RANK = BattleRecordMetricId("highest_rank")
    val CURRENT_FLOOR = BattleRecordMetricId("current_floor")
    val HIGHEST_FLOOR = BattleRecordMetricId("highest_floor")
    val BEST_SCORE = BattleRecordMetricId("best_score")
}

internal data class BattleRecordStats(
    val key: BattleRecordKey,
    val totalWins: Long = 0,
    val totalLosses: Long = 0,
    val currentWinStreak: Int = 0,
    val bestWinStreak: Int = 0,
    val progressMetrics: Map<BattleRecordMetricId, Long> = emptyMap(),
    val bestMetrics: Map<BattleRecordMetricId, Long> = emptyMap(),
) {
    init {
        require(totalWins >= 0 && totalLosses >= 0) { "Win and loss totals must be non-negative" }
        require(currentWinStreak >= 0) { "Current win streak must be non-negative" }
        require(bestWinStreak >= currentWinStreak) { "Best win streak cannot be below current win streak" }
        require(progressMetrics.values.all { it >= 0 }) { "Progress metrics must be non-negative" }
        require(bestMetrics.values.all { it >= 0 }) { "Best metrics must be non-negative" }
    }
}

private val RECORD_ID = Regex("[a-z0-9][a-z0-9_.-]{0,63}")

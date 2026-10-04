package jbro.cobblemon.mcc.internal.record

import java.util.UUID
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds

data class BattleRecordCategory(
    val contentId: String,
    val formatId: String,
) {
    init {
        require(ManagedBattleContentIds.isValid(contentId)) { "Invalid record content ID: $contentId" }
        require(RECORD_ID.matches(formatId)) { "Invalid record format ID: $formatId" }
    }
}

@JvmInline
value class BattleRecordMetricId(val value: String) {
    init {
        require(RECORD_ID.matches(value)) { "Invalid record metric ID: $value" }
    }
}

data class BattleRecordKey(
    val playerId: UUID,
    val category: BattleRecordCategory,
)

enum class BattleRecordOutcome { WIN, LOSS }

data class BattleRecordCompletion(
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

object BattleRecordMetrics {
    val CURRENT_FLOOR = BattleRecordMetricId("current_floor")
    val HIGHEST_FLOOR = BattleRecordMetricId("highest_floor")
    val BEST_SCORE = BattleRecordMetricId("best_score")
}

data class BattleRecordStats(
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

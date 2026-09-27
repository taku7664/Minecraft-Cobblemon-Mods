package jbro.cobblemon.mcc.client.hub

import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.hub.BattleHubRecordView
import jbro.cobblemon.uikit.UiRect

/** What the dashboard shows, derived from the viewer's own records without any client lookups. */
data class MccDashboardPresentation(
    val battles: Long,
    val wins: Long,
    val winRatePercent: Int?,
    val rows: List<Row>,
) {
    data class Row(
        val contentNameKey: String,
        val contentId: String,
        val formatNameKey: String,
        val formatId: String,
        val wins: Long,
        val losses: Long,
        val currentStreak: Int,
        val bestStreak: Int,
        /** Metric translation key to value, in the order the server sent them. */
        val metrics: List<Pair<String, Long>>,
    )

    companion object {
        private val PREFIX = "screen.${MoreCobblemonContents.MOD_ID}"

        /** [contentOrder] ranks content IDs, normally by their hub tab order; unknown contents sort last. */
        fun from(records: List<BattleHubRecordView>, contentOrder: (String) -> Int = { Int.MAX_VALUE }): MccDashboardPresentation {
            val battles = records.sumOf { it.battles }
            val wins = records.sumOf { it.wins }
            val rows = records
                .sortedWith(compareBy({ contentOrder(it.contentId) }, { it.contentId }, { it.formatId }))
                .map { record ->
                    Row(
                        contentNameKey = contentNameKey(record.contentId),
                        contentId = record.contentId,
                        formatNameKey = "$PREFIX.dashboard.format.${record.formatId}",
                        formatId = record.formatId,
                        wins = record.wins,
                        losses = record.losses,
                        currentStreak = record.currentStreak,
                        bestStreak = record.bestStreak,
                        metrics = record.bestMetrics.map { (id, value) -> "$PREFIX.dashboard.metric.$id" to value },
                    )
                }
            return MccDashboardPresentation(
                battles = battles,
                wins = wins,
                winRatePercent = if (battles == 0L) null else ((wins * 100 + battles / 2) / battles).toInt(),
                rows = rows,
            )
        }

        fun contentNameKey(contentId: String): String = "$PREFIX.hub.tab.${contentId.substringAfter(':')}"
    }
}

/** Logical layout of the dashboard inside whatever rectangle of the hub content area it is given. */
data class MccDashboardLayout(
    val trainer: UiRect,
    val model: UiRect,
    val nameLine: Int,
    val records: UiRect,
    val summary: UiRect,
    val rows: UiRect,
) {
    companion object {
        const val ROW_HEIGHT = 22
        private const val NAME_ROWS = 13

        fun calculate(bounds: UiRect): MccDashboardLayout {
            val gap = MccHubKit.GAP
            val trainerWidth = (bounds.width * 34 / 100).coerceIn(96, 150).coerceAtMost((bounds.width - gap - 60).coerceAtLeast(1))
            val trainer = UiRect(bounds.x, bounds.y, trainerWidth, bounds.height)
            val records = UiRect(trainer.right + gap, bounds.y, (bounds.width - trainerWidth - gap).coerceAtLeast(1), bounds.height)
            val trainerBody = MccHubKit.cardBody(trainer)
            val model = UiRect(trainerBody.x, trainerBody.y, trainerBody.width, (trainerBody.height - NAME_ROWS).coerceAtLeast(16))
            val recordsBody = MccHubKit.cardBody(records)
            val summary = UiRect(recordsBody.x, recordsBody.y, recordsBody.width, 22)
            val rows = UiRect(recordsBody.x - 2, summary.bottom + 4, recordsBody.width + 4,
                (recordsBody.bottom + 2 - summary.bottom - 4).coerceAtLeast(1))
            return MccDashboardLayout(trainer, model, model.bottom + 4, records, summary, rows)
        }
    }
}

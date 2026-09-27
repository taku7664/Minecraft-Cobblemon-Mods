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

/** Logical layout of the dashboard inside the hub content area. */
data class MccDashboardLayout(
    val trainer: UiRect,
    val trainerStrip: UiRect,
    val model: UiRect,
    val nameLine: Int,
    val detailLine: Int,
    val records: UiRect,
    val recordsStrip: UiRect,
    val summary: UiRect,
    val rows: UiRect,
) {
    companion object {
        const val ROW_HEIGHT = 22
        private const val GAP = 5
        private const val STRIP_HEIGHT = 15

        fun calculate(bounds: UiRect): MccDashboardLayout {
            val trainerWidth = (bounds.width * 34 / 100).coerceIn(96, 150).coerceAtMost((bounds.width - GAP - 60).coerceAtLeast(1))
            val trainer = UiRect(bounds.x, bounds.y, trainerWidth, bounds.height)
            val records = UiRect(trainer.right + GAP, bounds.y, (bounds.width - trainerWidth - GAP).coerceAtLeast(1), bounds.height)
            val trainerStrip = UiRect(trainer.x + 2, trainer.y + 2, (trainer.width - 4).coerceAtLeast(1), STRIP_HEIGHT)
            val modelTop = trainerStrip.bottom + 5
            val model = UiRect(trainer.x + 8, modelTop, (trainer.width - 16).coerceAtLeast(1),
                (trainer.bottom - 20 - modelTop).coerceAtLeast(16))
            val recordsStrip = UiRect(records.x + 2, records.y + 2, (records.width - 4).coerceAtLeast(1), STRIP_HEIGHT)
            val summary = UiRect(records.x + 6, recordsStrip.bottom + 4, (records.width - 12).coerceAtLeast(1), 22)
            val rowsTop = summary.bottom + 4
            val rows = UiRect(records.x + 4, rowsTop, (records.width - 8).coerceAtLeast(1), (records.bottom - 4 - rowsTop).coerceAtLeast(1))
            return MccDashboardLayout(trainer, trainerStrip, model, model.bottom + 5, model.bottom + 16,
                records, recordsStrip, summary, rows)
        }
    }
}

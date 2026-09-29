package jbro.cobblemon.mcc.client.hub

import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.hub.BattleHubRecordView
import jbro.cobblemon.uikit.UiLayout
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
            val layout = UiLayout.row(gap = MccHubKit.GAP) {
                percent(34, UiLayout.layers(UiLayout.leaf("trainer"), MccHubKit.cardBodyOf(UiLayout.column {
                    weight("model", min = 16)
                    space(NAME_ROWS)
                })), min = 96, max = 150)
                // Below the summary the record rows reach two pixels past the card body on three sides.
                weight(UiLayout.layers(UiLayout.leaf("records"), MccHubKit.cardBodyOf(UiLayout.layers(
                    UiLayout.column { fixed(22, "summary") },
                    UiLayout.inset(UiLayout.leaf("rows"), left = -2, top = 26, right = -2, bottom = -2, min = 1),
                ))), min = 1, reserve = 60)
            }.solve(bounds)
            val model = layout["model"]
            return MccDashboardLayout(layout["trainer"], model, model.bottom + 4, layout["records"], layout["summary"], layout["rows"])
        }
    }
}

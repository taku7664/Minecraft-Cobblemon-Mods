package jbro.cobblemon.mcc.client.hub

import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.api.hub.MccDashboardCard
import jbro.cobblemon.mcc.internal.hub.BattleHubDashboardPayload
import jbro.cobblemon.uikit.UiLayout
import jbro.cobblemon.uikit.UiRect

/**
 * What the dashboard shows: the totals over every record and one card per content, in hub tab order, with the
 * height each card takes in the records list.
 */
data class MccDashboardPresentation(
    val battles: Long,
    val wins: Long,
    val winRatePercent: Int?,
    val cards: List<Card>,
) {
    data class Card(val card: MccDashboardCard, val top: Int, val height: Int)

    val contentHeight: Int get() = cards.lastOrNull()?.let { it.top + it.height } ?: 0

    companion object {
        private val PREFIX = "screen.${MoreCobblemonContents.MOD_ID}"
        const val HEADER = 15
        const val STATS = 22
        const val ROW = 11
        const val ROW_DETAIL = 20
        const val NOTE = 11
        const val PADDING = 3
        const val GAP = 4

        fun height(card: MccDashboardCard): Int =
            HEADER + (if (card.stats.isEmpty()) 0 else STATS) +
                card.rows.sumOf { if (it.detail == null) ROW else ROW_DETAIL } +
                (if (card.note == null) 0 else NOTE) + PADDING

        /** [contentOrder] ranks content IDs, normally by their hub tab order; unknown contents sort last. */
        fun from(payload: BattleHubDashboardPayload?, contentOrder: (String) -> Int = { Int.MAX_VALUE }): MccDashboardPresentation {
            val battles = payload?.battles ?: 0L
            val wins = payload?.wins ?: 0L
            var top = 0
            val cards = payload?.cards.orEmpty()
                .sortedWith(compareBy({ contentOrder(it.contentId) }, { it.contentId }))
                .map { card -> Card(card, top, height(card)).also { top += it.height + GAP } }
            return MccDashboardPresentation(
                battles = battles,
                wins = wins,
                winRatePercent = if (battles == 0L) null else ((wins * 100 + battles / 2) / battles).toInt(),
                cards = cards,
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

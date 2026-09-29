package jbro.cobblemon.battleui.extended.ui.shared

import jbro.cobblemon.uikit.UiCrossAlignment
import jbro.cobblemon.uikit.UiInsets
import jbro.cobblemon.uikit.UiLayout
import jbro.cobblemon.uikit.UiRect

/** One responsive layout result for the switch renderer and Cobblemon's native click targets. */
object BattleSwitchLayout {
    private const val PANEL_WIDTH = 401
    private const val PANEL_HEIGHT = 150
    private const val SIDE_WIDTH = 105
    private const val HEADER_HEIGHT = 17
    private const val ROW_HEIGHT = 20
    private const val ROW_GAP = 2

    data class Result(
        val panel: UiRect,
        val allyHeader: UiRect,
        val detailHeader: UiRect,
        val opponentHeader: UiRect?,
        val detailBody: UiRect,
        val allies: List<UiRect>,
        val opponents: List<UiRect>,
        val back: UiRect,
    )

    @JvmStatic
    fun calculate(screenWidth: Int, screenHeight: Int): Result {
        require(screenWidth >= 0 && screenHeight >= 0)
        val panelWidth = minOf(PANEL_WIDTH, (screenWidth - 20).coerceAtLeast(0))
        val y = minOf(maxOf(70, (screenHeight - PANEL_HEIGHT) / 2 - 12),
            (screenHeight - PANEL_HEIGHT - 8).coerceAtLeast(0))
        val panel = UiLayout.align(UiLayout.leaf("panel"), width = panelWidth,
            height = PANEL_HEIGHT, fit = true).solve(UiRect(0, y, screenWidth, PANEL_HEIGHT))["panel"]
        val hasOpponent = screenWidth >= 400
        val columns = UiLayout.row(gap = 6) {
            fixed(SIDE_WIDTH, "ally")
            weight("detail", min = 0)
            if (hasOpponent) fixed(SIDE_WIDTH, "opponent")
        }.solve(panel)
        fun rows(column: UiRect): List<UiRect> = UiLayout.column(
            gap = ROW_GAP, padding = UiInsets(0, HEADER_HEIGHT, 0, 0)) {
            repeat(6) { fixed(ROW_HEIGHT, "row.$it") }
        }.solve(column).list("row")
        fun header(column: UiRect) = UiRect(column.x, column.y, column.width, HEADER_HEIGHT)
        val detail = columns["detail"]
        val opponent = columns.find("opponent")
        val backColumn = opponent ?: detail
        val back = UiLayout.align(UiLayout.leaf("back"), width = 34,
            height = HEADER_HEIGHT, horizontal = UiCrossAlignment.END)
            .solve(header(backColumn))["back"]
        return Result(panel, header(columns["ally"]), header(detail), opponent?.let(::header),
            UiLayout.inset(UiLayout.leaf("body"), top = HEADER_HEIGHT, bottom = 8)
                .solve(detail)["body"], rows(columns["ally"]), opponent?.let(::rows) ?: emptyList(), back)
    }
}

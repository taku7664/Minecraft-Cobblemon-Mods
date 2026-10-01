package jbro.cobblemon.ui.extended.ui.shared

import jbro.cobblemon.uikit.UiCrossAlignment
import jbro.cobblemon.uikit.UiInsets
import jbro.cobblemon.uikit.UiLayout
import jbro.cobblemon.uikit.UiRect

/** One UI Kit layout result shared by target rendering, mouse hitboxes, and keyboard navigation. */
object BattleTargetLayout {
    data class Result(
        val panel: UiRect,
        val opponents: List<UiRect>,
        val allies: List<UiRect>,
        val back: UiRect,
    )

    @JvmStatic
    fun calculate(screenWidth: Int, screenHeight: Int, slotsPerSide: Int): Result {
        require(slotsPerSide in 2..3)
        require(screenWidth >= 0 && screenHeight >= 0)
        val height = 84
        val y = maxOf(0, minOf(maxOf(70, screenHeight - height - 30), screenHeight - height - 8))
        val preferredWidth = if (slotsPerSide == 3) 310 else 248
        val panel = UiLayout.responsive { size ->
            UiLayout.align(UiLayout.leaf("panel"), width = minOf(preferredWidth, maxOf(0, size.width - 20)),
                height = height)
        }.solve(UiRect(0, y, screenWidth, height))["panel"]
        val rowHeight = 19
        val row = UiLayout.responsive { size ->
            val gap = if (size.width < 220) 4 else 8
            UiLayout.row(gap = gap, padding = UiInsets(8, 0, 8, 0)) {
                repeat(slotsPerSide) { weight("slot.$it") }
            }
        }
        fun fieldRow(top: Int): List<UiRect> =
            row.solve(UiRect(panel.x, panel.y + top, panel.width, rowHeight)).list("slot")
        val back = UiLayout.align(UiLayout.leaf("back"), width = 38, height = 12,
            horizontal = UiCrossAlignment.END).solve(UiRect(panel.x + 5, panel.y + 5,
            maxOf(0, panel.width - 10), 12))["back"]
        return Result(panel, fieldRow(31), fieldRow(62), back)
    }

    @JvmStatic
    fun card(slot: UiRect): UiRect = slot
}

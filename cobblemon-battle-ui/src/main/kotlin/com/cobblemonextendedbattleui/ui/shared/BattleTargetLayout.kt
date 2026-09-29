package jbro.cobblemon.battleui.extended.ui.shared

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
        val height = 96
        val y = maxOf(0, minOf(maxOf(70, screenHeight - height - 30), screenHeight - height - 8))
        val preferredWidth = if (slotsPerSide == 3) 246 else 180
        val panel = UiLayout.responsive { size ->
            UiLayout.align(UiLayout.leaf("panel"), width = minOf(preferredWidth, maxOf(0, size.width - 20)),
                height = height)
        }.solve(UiRect(0, y, screenWidth, height))["panel"]
        val rowHeight = if (slotsPerSide == 3) 18 else 20
        val row = UiLayout.responsive { size ->
            val gap = if (size.width < 150) 4 else if (slotsPerSide == 3) 8 else 10
            UiLayout.row(gap = gap, padding = UiInsets(10, 0, 10, 0)) {
                repeat(slotsPerSide) { weight("slot.$it") }
            }
        }
        fun fieldRow(top: Int): List<UiRect> =
            row.solve(UiRect(panel.x, panel.y + top, panel.width, rowHeight)).list("slot")
        val back = UiLayout.align(UiLayout.leaf("back"), width = 40, height = 16,
            horizontal = UiCrossAlignment.END).solve(UiRect(panel.x + 10, panel.y + 6,
            maxOf(0, panel.width - 20), 16))["back"]
        return Result(panel, fieldRow(36), fieldRow(71), back)
    }

    @JvmStatic
    fun card(slot: UiRect): UiRect = UiLayout.align(UiLayout.leaf("card"),
        width = minOf(slot.width, 70), height = slot.height).solve(slot)["card"]
}

package jbro.cobblemon.battleui.extended.ui.shared

import jbro.cobblemon.uikit.UiCrossAlignment
import jbro.cobblemon.uikit.UiLayout
import jbro.cobblemon.uikit.UiRect

/** Bottom-right battle actions laid out once for rendering, mouse hitboxes, and keyboard focus. */
object BattleControlLayout {
    @JvmStatic
    fun vertical(screenWidth: Int, screenHeight: Int, buttonWidth: Int, buttonHeight: Int,
                 gap: Int, rightMargin: Int, bottomMargin: Int, count: Int): List<UiRect> {
        require(screenWidth >= 0 && screenHeight >= 0 && buttonWidth >= 0 && buttonHeight >= 0)
        require(gap >= 0 && rightMargin >= 0 && bottomMargin >= 0 && count >= 0)
        val height = if (count == 0) 0 else count * buttonHeight + (count - 1) * gap
        val tree = UiLayout.responsive { _ ->
            UiLayout.align(
                UiLayout.column(gap = gap) {
                    repeat(count) { fixed(buttonHeight, "button.$it") }
                },
                width = buttonWidth,
                height = height,
                horizontal = UiCrossAlignment.END,
                vertical = UiCrossAlignment.END,
                fit = true,
                pinStart = true,
            )
        }
        return tree.solve(UiRect(0, 0,
            maxOf(0, screenWidth - rightMargin),
            maxOf(0, screenHeight - bottomMargin))).list("button")
    }
}

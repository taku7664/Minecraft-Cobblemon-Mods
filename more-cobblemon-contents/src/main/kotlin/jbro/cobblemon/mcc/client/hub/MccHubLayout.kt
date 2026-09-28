package jbro.cobblemon.mcc.client.hub

import jbro.cobblemon.uikit.UiCross
import jbro.cobblemon.uikit.UiInsets
import jbro.cobblemon.uikit.UiLayout
import jbro.cobblemon.uikit.UiRect

/** Logical GUI coordinates of the hub chrome; Minecraft applies the GUI scale afterward. */
data class MccHubLayout(
    val shell: UiRect,
    val header: UiRect,
    val rail: UiRect,
    val content: UiRect,
    val closeButton: UiRect,
    val balance: UiRect,
    val brandScale: Float,
    /** [TAB_HEIGHT], or [COMPACT_TAB_HEIGHT] when the rail cannot hold every tab at full height. */
    val tabHeight: Int = TAB_HEIGHT,
) {
    val compactTabs: Boolean get() = tabHeight < TAB_HEIGHT

    /** The rail's tab buttons that fit, top to bottom. */
    fun tabButtons(): List<UiRect> {
        val keys = UiLayout.keys("tab", visibleTabCount())
        return UiLayout.column(gap = TAB_GAP, padding = UiInsets.all(RAIL_INSET)) { keys.forEach { fixed(tabHeight, it) } }
            .solve(rail).list("tab")
    }

    fun tabButton(index: Int): UiRect = tabButtons()[index]

    fun visibleTabCount(): Int = tabsFitting(rail, tabHeight)

    companion object {
        const val TAB_HEIGHT = 26

        /** A small Pixel League control, for a rail with more tabs than fit at [TAB_HEIGHT]. */
        const val COMPACT_TAB_HEIGHT = 20
        const val TAB_GAP = 3
        /** Room between the rail window's frame and its rows. */
        const val RAIL_INSET = 6
        private const val GAP = 5
        private const val MAX_WIDTH = 720
        private const val MAX_HEIGHT = 400

        private fun tabsFitting(rail: UiRect, tabHeight: Int): Int =
            UiLayout.fittingCount(rail.height - RAIL_INSET * 2, tabHeight, TAB_GAP)

        fun calculate(screenWidth: Int, screenHeight: Int, tabCount: Int = 0): MccHubLayout {
            require(screenWidth > 0 && screenHeight > 0)
            val shellWidth = (screenWidth - 12).coerceIn(1, MAX_WIDTH)
            val shellHeight = (screenHeight - 8).coerceIn(1, MAX_HEIGHT)
            val headerHeight = if (shellHeight >= 220) 32 else 26
            val layout = UiLayout.align(UiLayout.layers(UiLayout.leaf("shell"), UiLayout.column(gap = GAP, padding = UiInsets(0, 4, 0, 6)) {
                fixed(headerHeight, UiLayout.inset(UiLayout.layers(UiLayout.leaf("header"), UiLayout.row(padding = UiInsets(0, 0, 5, 0)) {
                    spring()
                    fixed(90, "balance", UiCross(before = 4, after = 4))
                    space(6)
                    fixed(52, "close", UiCross.centered(TAB_HEIGHT))
                }), left = 4, right = 4))
                weight(UiLayout.row(gap = GAP, padding = UiInsets(6, 0, 6, 0)) {
                    percent(22, "rail", min = 84, max = 120)
                    weight("content", min = 1)
                }, min = 1)
            }), shellWidth, shellHeight).solve(UiRect(0, 0, screenWidth, screenHeight))
            val rail = layout["rail"]
            val tabHeight = if (tabsFitting(rail, TAB_HEIGHT) >= tabCount) TAB_HEIGHT else COMPACT_TAB_HEIGHT
            return MccHubLayout(layout["shell"], layout["header"], rail, layout["content"], layout["close"], layout["balance"],
                if (headerHeight >= 32) 2f else 1.5f, tabHeight)
        }
    }
}

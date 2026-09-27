package jbro.cobblemon.mcc.client.hub

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

    fun tabButton(index: Int): UiRect = UiRect(
        rail.x + RAIL_INSET,
        rail.y + RAIL_INSET + index * (tabHeight + TAB_GAP),
        rail.width - RAIL_INSET * 2,
        tabHeight,
    )

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
            ((rail.height - RAIL_INSET * 2 + TAB_GAP) / (tabHeight + TAB_GAP)).coerceAtLeast(0)

        fun calculate(screenWidth: Int, screenHeight: Int, tabCount: Int = 0): MccHubLayout {
            require(screenWidth > 0 && screenHeight > 0)
            val shellWidth = (screenWidth - 12).coerceIn(1, MAX_WIDTH)
            val shellHeight = (screenHeight - 8).coerceIn(1, MAX_HEIGHT)
            val shell = UiRect((screenWidth - shellWidth) / 2, (screenHeight - shellHeight) / 2, shellWidth, shellHeight)
            val headerHeight = if (shellHeight >= 220) 32 else 26
            val header = UiRect(shell.x + 4, shell.y + 4, shell.width - 8, headerHeight)
            val bodyTop = header.bottom + GAP
            val bodyHeight = (shell.bottom - 6 - bodyTop).coerceAtLeast(1)
            val railWidth = (shell.width * 22 / 100).coerceIn(84, 120)
            val rail = UiRect(shell.x + 6, bodyTop, railWidth, bodyHeight)
            val contentLeft = rail.right + GAP
            val content = UiRect(contentLeft, bodyTop, (shell.right - 6 - contentLeft).coerceAtLeast(1), bodyHeight)
            val closeWidth = 52
            val closeButton = UiRect(header.right - closeWidth - 5, header.y + (header.height - TAB_HEIGHT) / 2, closeWidth, TAB_HEIGHT)
            val balanceWidth = 90
            val balance = UiRect(closeButton.x - balanceWidth - 6, header.y + 4, balanceWidth, header.height - 8)
            val tabHeight = if (tabsFitting(rail, TAB_HEIGHT) >= tabCount) TAB_HEIGHT else COMPACT_TAB_HEIGHT
            return MccHubLayout(shell, header, rail, content, closeButton, balance, if (headerHeight >= 32) 2f else 1.5f, tabHeight)
        }
    }
}

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
) {
    fun tabButton(index: Int): UiRect = UiRect(
        rail.x + RAIL_INSET,
        rail.y + RAIL_INSET + index * (TAB_HEIGHT + TAB_GAP),
        rail.width - RAIL_INSET * 2,
        TAB_HEIGHT,
    )

    fun visibleTabCount(): Int = ((rail.height - RAIL_INSET * 2 + TAB_GAP) / (TAB_HEIGHT + TAB_GAP)).coerceAtLeast(0)

    companion object {
        const val TAB_HEIGHT = 26
        const val TAB_GAP = 3
        const val RAIL_INSET = 4
        private const val GAP = 5
        private const val MAX_WIDTH = 720
        private const val MAX_HEIGHT = 400

        fun calculate(screenWidth: Int, screenHeight: Int): MccHubLayout {
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
            return MccHubLayout(shell, header, rail, content, closeButton, balance, if (headerHeight >= 32) 2f else 1.5f)
        }
    }
}

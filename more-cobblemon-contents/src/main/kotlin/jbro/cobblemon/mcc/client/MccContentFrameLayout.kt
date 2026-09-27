package jbro.cobblemon.mcc.client

internal class MccContentFrameLayout private constructor(
    val shell: MccRect,
    val header: MccRect,
    val helpButton: MccRect,
    val closeButton: MccRect,
    val tabs: MccRect,
    val content: MccRect,
) {
    fun tabButtons(count: Int): List<MccRect> = partition(tabs, count, TAB_GAP)

    private fun partition(bounds: MccRect, count: Int, gap: Int): List<MccRect> {
        require(count > 0)
        val available = bounds.width - gap * (count - 1)
        return List(count) { index ->
            val start = available * index / count
            val end = available * (index + 1) / count
            MccRect(bounds.left + start + gap * index, bounds.top, end - start, bounds.height)
        }
    }

    internal companion object {
        fun calculate(screenWidth: Int, screenHeight: Int): MccContentFrameLayout {
            val shellWidth = (screenWidth - SCREEN_MARGIN * 2).coerceAtMost(MAX_SHELL_WIDTH)
            val shellHeight = (screenHeight - SCREEN_MARGIN * 2).coerceAtMost(MAX_SHELL_HEIGHT)
            require(shellWidth >= MIN_SHELL_WIDTH) { "Screen is too narrow for the MCC content frame" }
            require(shellHeight >= MIN_SHELL_HEIGHT) { "Screen is too short for the MCC content frame" }

            val shell = MccRect(
                (screenWidth - shellWidth) / 2,
                (screenHeight - shellHeight) / 2,
                shellWidth,
                shellHeight,
            )
            val header = MccRect(shell.left + INSET, shell.top + INSET, shell.width - INSET * 2, HEADER_HEIGHT)
            val closeButton = MccRect(header.right - CLOSE_SIZE, header.top, CLOSE_SIZE, header.height)
            val helpButton = MccRect(
                closeButton.left - HEADER_BUTTON_GAP - CLOSE_SIZE,
                header.top,
                CLOSE_SIZE,
                header.height,
            )
            val tabs = MccRect(header.left, header.bottom + SECTION_GAP, header.width, TAB_HEIGHT)
            val content = MccRect(
                tabs.left,
                tabs.bottom + SECTION_GAP,
                tabs.width,
                shell.bottom - INSET - tabs.bottom - SECTION_GAP,
            )
            return MccContentFrameLayout(shell, header, helpButton, closeButton, tabs, content)
        }
    }
}

private const val SCREEN_MARGIN = 8
private const val MAX_SHELL_WIDTH = 620
private const val MAX_SHELL_HEIGHT = 360
private const val MIN_SHELL_WIDTH = 284
private const val MIN_SHELL_HEIGHT = 200
private const val INSET = 6
private const val HEADER_HEIGHT = 22
private const val TAB_HEIGHT = 22
private const val SECTION_GAP = 4
private const val TAB_GAP = 4
private const val CLOSE_SIZE = 22
private const val HEADER_BUTTON_GAP = 4

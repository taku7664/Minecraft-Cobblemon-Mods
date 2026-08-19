package jbro.cobblemon.morebattlecontent.client

internal class MbcContentFrameLayout private constructor(
    val shell: TowerPlayRect,
    val header: TowerPlayRect,
    val closeButton: TowerPlayRect,
    val tabs: TowerPlayRect,
    val content: TowerPlayRect,
) {
    fun tabButtons(count: Int): List<TowerPlayRect> = partition(tabs, count, TAB_GAP)

    private fun partition(bounds: TowerPlayRect, count: Int, gap: Int): List<TowerPlayRect> {
        require(count > 0)
        val available = bounds.width - gap * (count - 1)
        return List(count) { index ->
            val start = available * index / count
            val end = available * (index + 1) / count
            TowerPlayRect(bounds.left + start + gap * index, bounds.top, end - start, bounds.height)
        }
    }

    internal companion object {
        fun calculate(screenWidth: Int, screenHeight: Int): MbcContentFrameLayout {
            val shellWidth = (screenWidth - SCREEN_MARGIN * 2).coerceAtMost(MAX_SHELL_WIDTH)
            val shellHeight = (screenHeight - SCREEN_MARGIN * 2).coerceAtMost(MAX_SHELL_HEIGHT)
            require(shellWidth >= MIN_SHELL_WIDTH) { "Screen is too narrow for the MBC content frame" }
            require(shellHeight >= MIN_SHELL_HEIGHT) { "Screen is too short for the MBC content frame" }

            val shell = TowerPlayRect(
                (screenWidth - shellWidth) / 2,
                (screenHeight - shellHeight) / 2,
                shellWidth,
                shellHeight,
            )
            val header = TowerPlayRect(shell.left + INSET, shell.top + INSET, shell.width - INSET * 2, HEADER_HEIGHT)
            val closeButton = TowerPlayRect(header.right - CLOSE_SIZE, header.top, CLOSE_SIZE, header.height)
            val tabs = TowerPlayRect(header.left, header.bottom + SECTION_GAP, header.width, TAB_HEIGHT)
            val content = TowerPlayRect(
                tabs.left,
                tabs.bottom + SECTION_GAP,
                tabs.width,
                shell.bottom - INSET - tabs.bottom - SECTION_GAP,
            )
            return MbcContentFrameLayout(shell, header, closeButton, tabs, content)
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

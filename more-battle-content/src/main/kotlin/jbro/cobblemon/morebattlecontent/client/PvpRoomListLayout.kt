package jbro.cobblemon.morebattlecontent.client

internal class PvpRoomListLayout private constructor(
    val shell: TowerPlayRect,
    val listPanel: TowerPlayRect,
    val footer: TowerPlayRect,
    val summaryRight: Int,
    val refreshButton: TowerPlayRect,
) {
    fun actionButtons(count: Int): List<TowerPlayRect> = partition(footer.inset(4), count, 4)

    private fun partition(bounds: TowerPlayRect, count: Int, gap: Int): List<TowerPlayRect> {
        val available = bounds.width - gap * (count - 1)
        return List(count) { index ->
            val start = available * index / count
            val end = available * (index + 1) / count
            TowerPlayRect(bounds.left + start + gap * index, bounds.top, end - start, bounds.height)
        }
    }

    internal companion object {
        fun calculate(shell: TowerPlayRect): PvpRoomListLayout {
            require(shell.width >= 284) { "Content is too narrow for the PvP room list" }
            require(shell.height >= 145) { "Content is too short for the PvP room list" }
            val footer = TowerPlayRect(shell.left, shell.bottom - 28, shell.width, 28)
            val list = TowerPlayRect(shell.left, shell.top, shell.width, footer.top - shell.top - 4)
            val refresh = TowerPlayRect(
                left = list.right - HEADER_INSET - REFRESH_WIDTH,
                top = list.top + HEADER_BUTTON_TOP,
                width = REFRESH_WIDTH,
                height = HEADER_BUTTON_HEIGHT,
            )
            return PvpRoomListLayout(
                shell = shell,
                listPanel = list,
                footer = footer,
                summaryRight = refresh.left - HEADER_GAP,
                refreshButton = refresh,
            )
        }
    }
}

private const val HEADER_INSET = 6
private const val HEADER_GAP = 6
private const val REFRESH_WIDTH = 72
private const val HEADER_BUTTON_TOP = 3
private const val HEADER_BUTTON_HEIGHT = 18

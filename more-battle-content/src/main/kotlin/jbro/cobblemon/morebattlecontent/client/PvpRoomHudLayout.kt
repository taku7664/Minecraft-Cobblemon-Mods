package jbro.cobblemon.morebattlecontent.client

internal data class PvpRoomHudLayout(
    val panel: TowerPlayRect,
    val header: TowerPlayRect,
    val title: TowerPlayRect,
    val openButton: TowerPlayRect,
    val toggleButton: TowerPlayRect,
    val phaseRow: TowerPlayRect?,
    val leftSide: TowerPlayRect,
    val rightSide: TowerPlayRect,
    val spectatorHeading: TowerPlayRect?,
    val spectatorRows: List<TowerPlayRect>,
    val hiddenSpectatorCount: Int,
    val toggleLabel: String,
) {
    companion object {
        const val HOTBAR_CLEARANCE = 28
        const val MAX_VISIBLE_SPECTATORS = 6
        private const val SCREEN_MARGIN = 6
        private const val HEADER_HEIGHT = 18
        private const val CONTROL_GAP = 2
        private const val TOGGLE_WIDTH = 32
        private const val OPEN_WIDTH = 64
        private const val EXPANDED_WIDTH = 184
        private const val COLLAPSED_WIDTH = 150
        private const val CONTENT_INSET = 5
        private const val PHASE_HEIGHT = 10
        private const val SIDE_HEIGHT = 22
        private const val SPECTATOR_HEADING_HEIGHT = 10
        private const val SPECTATOR_ROW_HEIGHT = 10

        fun calculate(screenWidth: Int, screenHeight: Int, expanded: Boolean, spectatorCount: Int): PvpRoomHudLayout {
            require(screenWidth > 0 && screenHeight > 0)
            require(spectatorCount >= 0)
            val visibleSpectators = if (expanded) spectatorCount.coerceAtMost(MAX_VISIBLE_SPECTATORS) else 0
            val hiddenSpectators = if (expanded) (spectatorCount - visibleSpectators).coerceAtLeast(0) else 0
            val extraRow = if (hiddenSpectators > 0) 1 else 0
            val panelWidth = (if (expanded) EXPANDED_WIDTH else COLLAPSED_WIDTH)
                .coerceAtMost((screenWidth - SCREEN_MARGIN * 2).coerceAtLeast(1))
            val panelHeight = if (expanded) {
                HEADER_HEIGHT + CONTROL_GAP + PHASE_HEIGHT + SIDE_HEIGHT + SPECTATOR_HEADING_HEIGHT +
                    (visibleSpectators + extraRow) * SPECTATOR_ROW_HEIGHT + CONTENT_INSET
            } else {
                HEADER_HEIGHT
            }
            val panel = TowerPlayRect(
                left = (screenWidth - panelWidth - SCREEN_MARGIN).coerceAtLeast(0),
                top = (screenHeight - HOTBAR_CLEARANCE - panelHeight).coerceAtLeast(SCREEN_MARGIN),
                width = panelWidth,
                height = panelHeight,
            )
            val header = TowerPlayRect(panel.left, panel.top, panel.width, HEADER_HEIGHT)
            val toggle = TowerPlayRect(header.right - TOGGLE_WIDTH, header.top, TOGGLE_WIDTH, header.height)
            val open = TowerPlayRect(toggle.left - CONTROL_GAP - OPEN_WIDTH, header.top, OPEN_WIDTH, header.height)
            val title = TowerPlayRect(header.left + 4, header.top, (open.left - header.left - 6).coerceAtLeast(1), header.height)
            if (!expanded) {
                return PvpRoomHudLayout(
                    panel, header, title, open, toggle, null,
                    TowerPlayRect(0, 0, 0, 0), TowerPlayRect(0, 0, 0, 0), null,
                    emptyList(), 0, "+",
                )
            }

            val contentLeft = panel.left + CONTENT_INSET
            val contentWidth = (panel.width - CONTENT_INSET * 2).coerceAtLeast(2)
            val phase = TowerPlayRect(contentLeft, header.bottom + CONTROL_GAP, contentWidth, PHASE_HEIGHT)
            val sideTop = phase.bottom
            val sideGap = 4
            val leftWidth = (contentWidth - sideGap) / 2
            val left = TowerPlayRect(contentLeft, sideTop, leftWidth, SIDE_HEIGHT)
            val right = TowerPlayRect(left.right + sideGap, sideTop, contentWidth - leftWidth - sideGap, SIDE_HEIGHT)
            val spectatorHeading = TowerPlayRect(contentLeft, left.bottom, contentWidth, SPECTATOR_HEADING_HEIGHT)
            val rows = List(visibleSpectators) { index ->
                TowerPlayRect(
                    contentLeft,
                    spectatorHeading.bottom + index * SPECTATOR_ROW_HEIGHT,
                    contentWidth,
                    SPECTATOR_ROW_HEIGHT,
                )
            }
            return PvpRoomHudLayout(
                panel, header, title, open, toggle, phase, left, right, spectatorHeading,
                rows, hiddenSpectators, "-",
            )
        }
    }
}

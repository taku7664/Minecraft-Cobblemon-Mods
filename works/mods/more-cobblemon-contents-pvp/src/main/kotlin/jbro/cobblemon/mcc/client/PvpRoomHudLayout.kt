package jbro.cobblemon.mcc.client

import jbro.cobblemon.uikit.UiCrossAlignment
import jbro.cobblemon.uikit.UiLayout
import jbro.cobblemon.uikit.UiRect

/**
 * The room HUD in the lower right corner, above the hotbar: a header of controls and, expanded, the room at a glance.
 * It is drawn in the hub's look: the header a title bar inside the window's frame, each side a card with a rule.
 */
internal data class PvpRoomHudLayout(
    val panel: UiRect,
    val header: UiRect,
    /** The title bar, inside the window's frame. */
    val titleBar: UiRect,
    val title: UiRect,
    val openButton: UiRect,
    val toggleButton: UiRect,
    val phaseRow: UiRect?,
    val leftSide: UiRect,
    val rightSide: UiRect,
    val spectatorHeading: UiRect?,
    val spectatorRows: List<UiRect>,
    val hiddenSpectatorCount: Int,
    val toggleLabel: String,
) {
    companion object {
        const val HOTBAR_CLEARANCE = 28
        const val MAX_VISIBLE_SPECTATORS = 6
        private const val SCREEN_MARGIN = 6
        private const val HEADER_HEIGHT = 22
        /** The window's frame around the title bar, and the bar's padding around its controls. */
        private const val FRAME = 2
        private const val BAR_PADDING = 2
        private const val CONTROL_GAP = 2
        private const val TOGGLE_WIDTH = 32
        private const val OPEN_WIDTH = 64
        private const val EXPANDED_WIDTH = 184
        private const val COLLAPSED_WIDTH = 150
        private const val CONTENT_INSET = 5
        private const val PHASE_HEIGHT = 10
        private const val SIDE_HEIGHT = 28
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
            val rows = UiLayout.keys("spectator", visibleSpectators)
            val panel = UiLayout.layers(UiLayout.leaf("panel"), UiLayout.column {
                fixed(HEADER_HEIGHT, UiLayout.layers(UiLayout.leaf("header"), UiLayout.inset(UiLayout.layers(UiLayout.leaf("bar"),
                    UiLayout.inset(UiLayout.row {
                        space(4)
                        weight("title", min = 1)
                        space(CONTROL_GAP)
                        fixed(OPEN_WIDTH, "open")
                        space(CONTROL_GAP)
                        fixed(TOGGLE_WIDTH, "toggle")
                    }, top = BAR_PADDING, right = BAR_PADDING, bottom = BAR_PADDING, min = 1)),
                    left = FRAME, top = FRAME, right = FRAME, bottom = FRAME, min = 1)))
                if (expanded) {
                    space(CONTROL_GAP)
                    weight(UiLayout.inset(UiLayout.column {
                        fixed(PHASE_HEIGHT, "phase")
                        fixed(SIDE_HEIGHT, UiLayout.row(gap = 4) {
                            weight("left")
                            weight("right")
                        })
                        fixed(SPECTATOR_HEADING_HEIGHT, "heading")
                        rows.forEach { fixed(SPECTATOR_ROW_HEIGHT, it) }
                    }, left = CONTENT_INSET, right = CONTENT_INSET, min = 2))
                }
            })
            // Pinned to the lower right, but never off the left or top edge of a tiny screen.
            val layout = UiLayout.inset(UiLayout.align(panel, panelWidth, panelHeight, UiCrossAlignment.END, UiCrossAlignment.END,
                pinStart = true), top = SCREEN_MARGIN, right = SCREEN_MARGIN, bottom = HOTBAR_CLEARANCE)
                .solve(UiRect(0, 0, screenWidth, screenHeight))
            val header = layout["header"]
            if (!expanded) {
                return PvpRoomHudLayout(
                    layout["panel"], header, layout["bar"], layout["title"], layout["open"], layout["toggle"], null,
                    UiRect(0, 0, 0, 0), UiRect(0, 0, 0, 0), null,
                    emptyList(), 0, "+",
                )
            }
            return PvpRoomHudLayout(
                layout["panel"], header, layout["bar"], layout["title"], layout["open"], layout["toggle"], layout["phase"], layout["left"],
                layout["right"], layout["heading"], layout.list("spectator"), hiddenSpectators, "-",
            )
        }
    }
}

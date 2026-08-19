package jbro.cobblemon.morebattlecontent.client

internal class PvpRoomLayout private constructor(
    val shell: TowerPlayRect,
    val header: TowerPlayRect,
    val closeButton: TowerPlayRect,
    val settings: TowerPlayRect,
    val visibilityGroup: TowerPlayRect,
    val formatGroup: TowerPlayRect,
    val mechanicsGroup: TowerPlayRect,
    val leftSeat: TowerPlayRect,
    val spectators: TowerPlayRect,
    val rightSeat: TowerPlayRect,
    val footer: TowerPlayRect,
) {
    val spectatorJoinButton = TowerPlayRect(
        spectators.left + PANEL_INSET,
        spectators.bottom - SPECTATOR_BUTTON_BOTTOM - CONTROL_HEIGHT,
        spectators.width - PANEL_INSET * 2,
        CONTROL_HEIGHT,
    )
    val spectatorGrid = TowerPlayRect(
        spectators.left + PANEL_INSET,
        spectators.top + SPECTATOR_GRID_TOP,
        spectators.width - PANEL_INSET * 2,
        (spectatorJoinButton.top - SECTION_GAP - spectators.top - SPECTATOR_GRID_TOP).coerceAtLeast(1),
    )

    fun visibilityButtons(): List<TowerPlayRect> = groupButtons(visibilityGroup, 2)

    fun formatButtons(): List<TowerPlayRect> = groupButtons(formatGroup, 2)

    fun mechanicButtons(): List<TowerPlayRect> = groupButtons(mechanicsGroup, 4)

    fun managementButtons(): List<TowerPlayRect> = partition(footer.inset(FOOTER_INSET), 4, CONTROL_GAP)

    fun seatButton(side: TowerPlayRect): TowerPlayRect = TowerPlayRect(
        side.left + PANEL_INSET,
        side.bottom - SEAT_BUTTON_BOTTOM - CONTROL_HEIGHT,
        side.width - PANEL_INSET * 2,
        CONTROL_HEIGHT,
    )

    private fun groupButtons(group: TowerPlayRect, count: Int): List<TowerPlayRect> = partition(
        TowerPlayRect(group.left + PANEL_INSET, group.bottom - PANEL_INSET - CONTROL_HEIGHT, group.width - PANEL_INSET * 2, CONTROL_HEIGHT),
        count,
        CONTROL_GAP,
    )

    private fun partition(bounds: TowerPlayRect, count: Int, gap: Int): List<TowerPlayRect> {
        val available = bounds.width - gap * (count - 1)
        return List(count) { index ->
            val start = available * index / count
            val end = available * (index + 1) / count
            TowerPlayRect(bounds.left + start + gap * index, bounds.top, end - start, bounds.height)
        }
    }

    internal companion object {
        fun calculate(screenWidth: Int, screenHeight: Int): PvpRoomLayout {
            val shellWidth = (screenWidth - SCREEN_MARGIN * 2).coerceAtMost(MAX_SHELL_WIDTH)
            val shellHeight = (screenHeight - SCREEN_MARGIN * 2).coerceAtMost(MAX_SHELL_HEIGHT)
            require(shellWidth >= MIN_SHELL_WIDTH) { "Screen is too narrow for the PvP room shell" }
            require(shellHeight >= MIN_SHELL_HEIGHT) { "Screen is too short for the PvP room shell" }

            val shell = TowerPlayRect(
                (screenWidth - shellWidth) / 2,
                (screenHeight - shellHeight) / 2,
                shellWidth,
                shellHeight,
            )
            val header = TowerPlayRect(shell.left + SHELL_INSET, shell.top + SHELL_INSET, shell.width - SHELL_INSET * 2, HEADER_HEIGHT)
            val closeButton = TowerPlayRect(header.right - HEADER_HEIGHT, header.top, HEADER_HEIGHT, HEADER_HEIGHT)
            val settings = TowerPlayRect(header.left, header.bottom + SECTION_GAP, header.width, SETTINGS_HEIGHT)
            val groupGap = SECTION_GAP
            val compactWidth = (settings.width * 23 / 100).coerceAtLeast(66)
            val visibilityGroup = TowerPlayRect(settings.left, settings.top, compactWidth, settings.height)
            val formatGroup = TowerPlayRect(visibilityGroup.right + groupGap, settings.top, compactWidth, settings.height)
            val mechanicsGroup = TowerPlayRect(formatGroup.right + groupGap, settings.top, settings.right - formatGroup.right - groupGap, settings.height)
            val footer = TowerPlayRect(header.left, shell.bottom - FOOTER_BOTTOM - FOOTER_HEIGHT, header.width, FOOTER_HEIGHT)
            val bodyTop = settings.bottom + SECTION_GAP
            val bodyHeight = footer.top - SECTION_GAP - bodyTop
            val bodyWidth = header.width
            val spectatorWidth = (bodyWidth * 40 / 100).coerceIn(MIN_SPECTATOR_WIDTH, MAX_SPECTATOR_WIDTH)
            val sideAvailable = bodyWidth - spectatorWidth - SECTION_GAP * 2
            val leftWidth = sideAvailable / 2
            val rightWidth = sideAvailable - leftWidth
            val left = TowerPlayRect(header.left, bodyTop, leftWidth, bodyHeight)
            val spectators = TowerPlayRect(left.right + SECTION_GAP, bodyTop, spectatorWidth, bodyHeight)
            val right = TowerPlayRect(spectators.right + SECTION_GAP, bodyTop, rightWidth, bodyHeight)
            return PvpRoomLayout(
                shell,
                header,
                closeButton,
                settings,
                visibilityGroup,
                formatGroup,
                mechanicsGroup,
                left,
                spectators,
                right,
                footer,
            )
        }
    }
}

private const val SCREEN_MARGIN = 8
private const val MAX_SHELL_WIDTH = 540
private const val MAX_SHELL_HEIGHT = 340
private const val MIN_SHELL_WIDTH = 304
private const val MIN_SHELL_HEIGHT = 200
private const val SHELL_INSET = 6
private const val HEADER_HEIGHT = 22
private const val SETTINGS_HEIGHT = 48
private const val FOOTER_HEIGHT = 28
private const val FOOTER_BOTTOM = 4
private const val SECTION_GAP = 4
private const val PANEL_INSET = 4
private const val FOOTER_INSET = 4
private const val CONTROL_GAP = 3
private const val CONTROL_HEIGHT = 18
private const val MIN_SPECTATOR_WIDTH = 116
private const val MAX_SPECTATOR_WIDTH = 220
private const val SPECTATOR_GRID_TOP = 21
private const val SPECTATOR_BUTTON_BOTTOM = 5
private const val SEAT_BUTTON_BOTTOM = 5

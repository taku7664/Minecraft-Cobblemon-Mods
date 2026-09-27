package jbro.cobblemon.mcc.client

internal enum class TowerPlayLayoutMode {
    WIDE,
    COMPACT,
}

internal class TowerPlayLayout private constructor(
    val mode: TowerPlayLayoutMode,
    val shell: MccRect,
    val partyPanel: MccRect,
    val mainPanel: MccRect,
    val detailsPanel: MccRect,
) {
    fun partyCard(index: Int): MccRect {
        require(index in 0 until PARTY_SIZE)
        val rail = MccRect(
            left = partyPanel.left + PANEL_INSET,
            top = partyPanel.top + PARTY_HEADING_HEIGHT,
            width = partyPanel.width - PANEL_INSET * 2,
            height = partyPanel.height - PARTY_HEADING_HEIGHT - PANEL_INSET,
        )
        val available = rail.height - PARTY_CARD_GAP * (PARTY_SIZE - 1)
        val start = available * index / PARTY_SIZE
        val end = available * (index + 1) / PARTY_SIZE
        return MccRect(
            left = rail.left,
            top = rail.top + start + PARTY_CARD_GAP * index,
            width = rail.width,
            height = (end - start).coerceAtLeast(MIN_PARTY_CARD_HEIGHT),
        )
    }

    fun partyCardContent(index: Int): MccPartyCardContentLayout {
        val card = partyCard(index)
        val portraitSize = (card.height - CARD_INSET * 2).coerceIn(MIN_PORTRAIT_SIZE, MAX_PORTRAIT_SIZE)
        val portrait = MccRect(
            left = card.left + CARD_INSET + SELECTED_STRIP_WIDTH,
            top = card.top + (card.height - portraitSize) / 2,
            width = portraitSize,
            height = portraitSize,
        )
        val textHeight = SUMMARY_LINE_HEIGHT * 2
        val nameTop = card.top + ((card.height - textHeight) / 2).coerceAtLeast(1)
        return MccPartyCardContentLayout(
            index = index,
            portrait = portrait,
            textLeft = portrait.right + CARD_CONTENT_GAP,
            textRight = card.right - CARD_INSET,
            nameTop = nameTop,
            detailsTop = nameTop + SUMMARY_LINE_HEIGHT,
        )
    }

    fun progressSegments(count: Int): List<MccRect> {
        require(count > 0)
        val track = MccRect(
            left = mainPanel.left + CONTROL_INSET,
            top = mainPanel.top + PROGRESS_TOP,
            width = mainPanel.width - CONTROL_INSET * 2,
            height = PROGRESS_HEIGHT,
        )
        return partition(track, count, PROGRESS_GAP)
    }

    fun formatButtons(): List<MccRect> = partition(
        MccRect(
            left = mainPanel.left + CONTROL_INSET,
            top = mainPanel.top + FORMAT_BUTTON_TOP,
            width = mainPanel.width - CONTROL_INSET * 2,
            height = CONTROL_HEIGHT,
        ),
        2,
        CONTROL_GAP,
    )

    fun mechanicButtons(count: Int): List<MccRect> = partition(
        MccRect(
            left = mainPanel.left + CONTROL_INSET,
            top = mainPanel.top + MECHANIC_BUTTON_TOP,
            width = mainPanel.width - CONTROL_INSET * 2,
            height = CONTROL_HEIGHT,
        ),
        count,
        CONTROL_GAP,
    )

    fun actionButtons(count: Int): List<MccRect> {
        require(count in 1..3)
        return partition(
            MccRect(
                detailsPanel.left + ACTION_INSET,
                detailsPanel.bottom - ACTION_INSET - ACTION_HEIGHT,
                detailsPanel.width - ACTION_INSET * 2,
                ACTION_HEIGHT,
            ),
            count,
            ACTION_GAP,
        )
    }

    private fun partition(bounds: MccRect, count: Int, gap: Int): List<MccRect> {
        require(count > 0)
        val available = bounds.width - gap * (count - 1)
        return (0 until count).map { index ->
            val start = available * index / count
            val end = available * (index + 1) / count
            MccRect(
                left = bounds.left + start + gap * index,
                top = bounds.top,
                width = end - start,
                height = bounds.height,
            )
        }
    }

    internal companion object {
        const val SUMMARY_LINE_HEIGHT = 9
        const val MAX_SUMMARY_LINES = 4
        const val FORMAT_LABEL_OFFSET = 31
        const val MECHANIC_LABEL_OFFSET = 65

        fun calculate(shell: MccRect): TowerPlayLayout {
            require(shell.width >= MIN_SHELL_WIDTH) { "Content is too narrow for the Battle Tower layout" }
            require(shell.height >= MIN_SHELL_HEIGHT) { "Content is too short for the Battle Tower layout" }
            val content = shell
            val mode = if (shell.width >= WIDE_THRESHOLD) TowerPlayLayoutMode.WIDE else TowerPlayLayoutMode.COMPACT
            val partyWidth = when (mode) {
                TowerPlayLayoutMode.WIDE -> (shell.width * 22 / 100).coerceIn(118, 150)
                TowerPlayLayoutMode.COMPACT -> (shell.width * 31 / 100).coerceIn(88, 104)
            }
            val partyPanel = MccRect(content.left, content.top, partyWidth, content.height)
            val workLeft = partyPanel.right + SECTION_GAP
            val workWidth = content.right - workLeft

            val mainPanel: MccRect
            val detailsPanel: MccRect
            if (mode == TowerPlayLayoutMode.WIDE) {
                val detailsWidth = (shell.width * 28 / 100).coerceIn(145, 190)
                mainPanel = MccRect(
                    left = workLeft,
                    top = content.top,
                    width = workWidth - detailsWidth - SECTION_GAP,
                    height = content.height,
                )
                detailsPanel = MccRect(
                    left = mainPanel.right + SECTION_GAP,
                    top = content.top,
                    width = detailsWidth,
                    height = content.height,
                )
            } else {
                val mainHeight = COMPACT_MAIN_MIN_HEIGHT
                    .coerceAtMost(content.height - COMPACT_DETAILS_MIN_HEIGHT - SECTION_GAP)
                mainPanel = MccRect(workLeft, content.top, workWidth, mainHeight)
                detailsPanel = MccRect(
                    left = workLeft,
                    top = mainPanel.bottom + SECTION_GAP,
                    width = workWidth,
                    height = content.bottom - mainPanel.bottom - SECTION_GAP,
                )
            }

            return TowerPlayLayout(mode, shell, partyPanel, mainPanel, detailsPanel)
        }
    }
}

private const val PARTY_SIZE = 6
private const val MIN_SHELL_WIDTH = 280
private const val MIN_SHELL_HEIGHT = 145
private const val WIDE_THRESHOLD = 460
private const val SECTION_GAP = 4
private const val PANEL_INSET = 4
private const val PARTY_HEADING_HEIGHT = 18
private const val PARTY_CARD_GAP = 2
private const val MIN_PARTY_CARD_HEIGHT = 16
private const val CARD_INSET = 2
private const val SELECTED_STRIP_WIDTH = 2
private const val CARD_CONTENT_GAP = 3
private const val MIN_PORTRAIT_SIZE = 14
private const val MAX_PORTRAIT_SIZE = 34
private const val CONTROL_INSET = 7
private const val CONTROL_HEIGHT = 18
private const val CONTROL_GAP = 3
private const val PROGRESS_TOP = 20
private const val PROGRESS_HEIGHT = 7
private const val PROGRESS_GAP = 2
private const val FORMAT_BUTTON_TOP = 41
private const val MECHANIC_BUTTON_TOP = 75
private const val ACTION_INSET = 5
private const val ACTION_HEIGHT = 18
private const val ACTION_GAP = 4
private const val COMPACT_MAIN_MIN_HEIGHT = 99
private const val COMPACT_DETAILS_MIN_HEIGHT = 38

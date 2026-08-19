package jbro.cobblemon.morebattlecontent.client

internal enum class FactoryPlayLayoutMode {
    WIDE,
    COMPACT,
}

internal data class FactoryRentalCardContentLayout(
    val portrait: TowerPlayRect,
    val textLeft: Int,
    val textRight: Int,
    val nameTop: Int,
    val detailsTop: Int,
) {
    companion object {
        fun calculate(card: TowerPlayRect): FactoryRentalCardContentLayout {
            val portraitSize = (card.height - CARD_CONTENT_INSET * 2)
                .coerceIn(MIN_PORTRAIT_SIZE, MAX_PORTRAIT_SIZE)
                .coerceAtMost((card.width / 2).coerceAtLeast(1))
            val portrait = TowerPlayRect(
                card.left + CARD_CONTENT_INSET,
                card.top + (card.height - portraitSize) / 2,
                portraitSize,
                portraitSize,
            )
            return FactoryRentalCardContentLayout(
                portrait = portrait,
                textLeft = portrait.right + CARD_CONTENT_GAP,
                textRight = card.right - CARD_CONTENT_INSET,
                nameTop = card.top + 5,
                detailsTop = card.bottom - 11,
            )
        }
    }
}

internal class FactoryPlayLayout private constructor(
    val mode: FactoryPlayLayoutMode,
    val shell: TowerPlayRect,
    val summary: TowerPlayRect,
    val content: TowerPlayRect,
    val footer: TowerPlayRect,
    val swapTeamArea: TowerPlayRect,
    val swapOfferArea: TowerPlayRect,
) {
    fun optionButtons(row: Int): List<TowerPlayRect> {
        require(row in 0..1)
        return partition(
            TowerPlayRect(
                content.left + PANEL_INSET,
                content.top + OPTION_TOP + row * OPTION_ROW_STEP,
                content.width - PANEL_INSET * 2,
                CONTROL_HEIGHT,
            ),
            2,
            CONTROL_GAP,
        )
    }

    fun mainCards(count: Int): List<TowerPlayRect> = cardGrid(
        TowerPlayRect(
            content.left + PANEL_INSET,
            content.top + CARD_HEADING_HEIGHT,
            content.width - PANEL_INSET * 2,
            content.height - CARD_HEADING_HEIGHT - PANEL_INSET,
        ),
        count,
        if (mode == FactoryPlayLayoutMode.WIDE) 3 else 2,
    )

    fun swapCards(teamCount: Int, offerCount: Int): Pair<List<TowerPlayRect>, List<TowerPlayRect>> =
        cardGrid(swapTeamArea.inset(SWAP_INSET), teamCount, 2, SWAP_HEADING_HEIGHT) to
            cardGrid(swapOfferArea.inset(SWAP_INSET), offerCount, 2, SWAP_HEADING_HEIGHT)

    fun actionButtons(count: Int): List<TowerPlayRect> {
        require(count in 1..4)
        return partition(footer.inset(FOOTER_INSET), count, ACTION_GAP)
    }

    private fun cardGrid(
        area: TowerPlayRect,
        count: Int,
        requestedColumns: Int,
        topInset: Int = 0,
    ): List<TowerPlayRect> {
        if (count == 0) return emptyList()
        val columns = requestedColumns.coerceAtMost(count)
        val rows = (count + columns - 1) / columns
        val grid = TowerPlayRect(area.left, area.top + topInset, area.width, (area.height - topInset).coerceAtLeast(1))
        val availableHeight = grid.height - CARD_GAP * (rows - 1)
        val cellHeight = (availableHeight / rows).coerceAtMost(MAX_CARD_HEIGHT).coerceAtLeast(1)
        val rowBlockHeight = cellHeight * rows + CARD_GAP * (rows - 1)
        val top = grid.top + ((grid.height - rowBlockHeight) / 2).coerceAtLeast(0)
        val widths = partition(TowerPlayRect(grid.left, top, grid.width, cellHeight), columns, CARD_GAP)
        return List(count) { index ->
            val column = index % columns
            val row = index / columns
            TowerPlayRect(widths[column].left, top + row * (cellHeight + CARD_GAP), widths[column].width, cellHeight)
        }
    }

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
        fun calculate(shell: TowerPlayRect): FactoryPlayLayout {
            require(shell.width >= MIN_SHELL_WIDTH) { "Content is too narrow for the Battle Factory layout" }
            require(shell.height >= MIN_SHELL_HEIGHT) { "Content is too short for the Battle Factory layout" }
            val summary = TowerPlayRect(shell.left, shell.top, shell.width, SUMMARY_HEIGHT)
            val footer = TowerPlayRect(shell.left, shell.bottom - FOOTER_HEIGHT, shell.width, FOOTER_HEIGHT)
            val contentTop = summary.bottom + SECTION_GAP
            val content = TowerPlayRect(
                shell.left,
                contentTop,
                shell.width,
                footer.top - SECTION_GAP - contentTop,
            )
            val mode = if (shell.width >= WIDE_THRESHOLD) FactoryPlayLayoutMode.WIDE else FactoryPlayLayoutMode.COMPACT
            val groupGap = SECTION_GAP
            val teamWidth = (content.width - groupGap) / 2
            val swapTeamArea = TowerPlayRect(content.left, content.top, teamWidth, content.height)
            val swapOfferArea = TowerPlayRect(
                swapTeamArea.right + groupGap,
                content.top,
                content.right - swapTeamArea.right - groupGap,
                content.height,
            )
            return FactoryPlayLayout(mode, shell, summary, content, footer, swapTeamArea, swapOfferArea)
        }
    }
}

private const val MIN_SHELL_WIDTH = 268
private const val MIN_SHELL_HEIGHT = 135
private const val WIDE_THRESHOLD = 460
private const val SUMMARY_HEIGHT = 32
private const val FOOTER_HEIGHT = 28
private const val SECTION_GAP = 4
private const val PANEL_INSET = 5
private const val OPTION_TOP = 24
private const val OPTION_ROW_STEP = 30
private const val CONTROL_HEIGHT = 20
private const val CONTROL_GAP = 5
private const val CARD_HEADING_HEIGHT = 18
private const val SWAP_INSET = 4
private const val SWAP_HEADING_HEIGHT = 16
private const val CARD_GAP = 4
private const val MAX_CARD_HEIGHT = 62
private const val FOOTER_INSET = 4
private const val ACTION_GAP = 4
private const val CARD_CONTENT_INSET = 4
private const val CARD_CONTENT_GAP = 5
private const val MIN_PORTRAIT_SIZE = 14
private const val MAX_PORTRAIT_SIZE = 48

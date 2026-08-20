package jbro.cobblemon.morebattlecontent.client

/**
 * Card geometry for the PvP entry selection panels. Both sides show six Pokemon, so the same
 * arithmetic serves the player's own party and the opponent's team preview.
 */
internal object PvpSelectionLayout {
    const val PARTY_SIZE = 6

    fun partyCard(panel: TowerPlayRect, index: Int): TowerPlayRect {
        require(index in 0 until PARTY_SIZE) { "PvP party card index is out of range" }
        val rail = TowerPlayRect(
            left = panel.left + PANEL_INSET,
            top = panel.top + HEADING_HEIGHT,
            width = (panel.width - PANEL_INSET * 2).coerceAtLeast(1),
            height = (panel.height - HEADING_HEIGHT - PANEL_INSET).coerceAtLeast(PARTY_SIZE),
        )
        val available = rail.height - CARD_GAP * (PARTY_SIZE - 1)
        val start = available * index / PARTY_SIZE
        val end = available * (index + 1) / PARTY_SIZE
        return TowerPlayRect(
            left = rail.left,
            top = rail.top + start + CARD_GAP * index,
            width = rail.width,
            height = (end - start).coerceAtLeast(MIN_CARD_HEIGHT),
        )
    }

    fun partyCardContent(panel: TowerPlayRect, index: Int): TowerPartyCardContentLayout {
        val card = partyCard(panel, index)
        val portraitSize = (card.height - CARD_INSET * 2).coerceIn(MIN_PORTRAIT_SIZE, MAX_PORTRAIT_SIZE)
        val portrait = TowerPlayRect(
            left = card.left + CARD_INSET + SELECTED_STRIP_WIDTH,
            top = card.top + (card.height - portraitSize) / 2,
            width = portraitSize,
            height = portraitSize,
        )
        val nameTop = card.top + ((card.height - SUMMARY_LINE_HEIGHT * 2) / 2).coerceAtLeast(1)
        return TowerPartyCardContentLayout(
            index = index,
            portrait = portrait,
            textLeft = portrait.right + CARD_CONTENT_GAP,
            textRight = (card.right - CARD_INSET).coerceAtLeast(portrait.right + CARD_CONTENT_GAP + 1),
            nameTop = nameTop,
            detailsTop = nameTop + SUMMARY_LINE_HEIGHT,
        )
    }

    private const val PANEL_INSET = 4
    private const val HEADING_HEIGHT = 18
    private const val CARD_GAP = 2
    private const val MIN_CARD_HEIGHT = 16
    private const val CARD_INSET = 2
    private const val SELECTED_STRIP_WIDTH = 2
    private const val CARD_CONTENT_GAP = 3
    private const val SUMMARY_LINE_HEIGHT = 9
    private const val MIN_PORTRAIT_SIZE = 14
    private const val MAX_PORTRAIT_SIZE = 34
}

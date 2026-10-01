package jbro.cobblemon.ui.extended.ui.champions

import jbro.cobblemon.ui.navigation.UiRect

/**
 * Where the battle information window and its three columns sit, in GUI pixels. The window is laid out at the GUI's
 * own scale, never shrunk to fit, so every glyph lands on whole pixels; it grows with the screen up to a comfortable
 * reading width instead.
 */
internal object ChampionsInfoLayout {
    const val MIN_WIDTH = 400
    const val MAX_WIDTH = 540
    const val MIN_HEIGHT = 220
    const val MAX_HEIGHT = 300
    const val MARGIN = 6
    const val HEADER = 20
    const val PADDING = 6
    const val GAP = 5

    data class Result(val window: UiRect, val ally: UiRect, val field: UiRect, val opponent: UiRect)

    fun calculate(screenWidth: Int, screenHeight: Int): Result {
        val width = (screenWidth - MARGIN * 2).coerceIn(MIN_WIDTH, MAX_WIDTH)
        val height = (screenHeight - MARGIN * 2).coerceIn(MIN_HEIGHT, MAX_HEIGHT)
        val x = (screenWidth - width) / 2
        val y = maxOf(0, (screenHeight - height) / 2)
        val bodyY = y + HEADER
        val bodyHeight = height - HEADER - PADDING
        val inner = width - PADDING * 2 - GAP * 2
        // The field column holds short effect rows; the side columns carry the Pokemon cards.
        val field = (inner * 0.29f).toInt()
        val side = (inner - field) / 2
        val allyX = x + PADDING
        val fieldX = allyX + side + GAP
        val opponentX = fieldX + field + GAP
        return Result(
            UiRect(x, y, width, height),
            UiRect(allyX, bodyY, side, bodyHeight),
            UiRect(fieldX, bodyY, field, bodyHeight),
            UiRect(opponentX, bodyY, x + width - PADDING - opponentX, bodyHeight)
        )
    }
}

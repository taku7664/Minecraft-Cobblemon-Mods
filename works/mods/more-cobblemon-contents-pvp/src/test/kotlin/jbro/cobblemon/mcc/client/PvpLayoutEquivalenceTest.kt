package jbro.cobblemon.mcc.client

import jbro.cobblemon.uikit.UiRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PvP's layouts moved onto the UI kit's layout tree. These compare them with the hand-written arithmetic they
 * replaced (kept below, copied from before the move) across a sweep of sizes.
 */
class PvpLayoutEquivalenceTest {
    @Test
    fun `the tab frame matches the hand-written layout`() {
        var compared = 0
        for (width in 1..760 step 3) for (height in 1..480 step 3) {
            val bounds = UiRect(13, 7, width, height)
            val old = legacy { legacyFrame(bounds) } ?: continue
            val new = PvpHubLayout.calculate(bounds)
            assertEquals(old, Triple(new.strip, new.body, new.footer), bounds.toString())
            compared++
        }
        assertTrue(compared > 1_000)
    }

    @Test
    fun `the spectator grid matches the hand-written layout`() {
        var compared = 0
        val names = listOf(listOf(30), listOf(20, 60, 44), List(12) { 18 + it * 5 }, List(25) { 40 })
        for (width in 1..400 step 3) for (height in 1..160 step 3) names.forEach { widths ->
            val bounds = UiRect(20, 30, width, height)
            val old = legacy { LegacySpectators.calculate(bounds, widths) } ?: return@forEach
            val new = PvpSpectatorGridLayout.calculate(bounds, widths)
            assertEquals(old.first, listOf(new.rows, new.columns), "$bounds $widths")
            assertEquals(old.second, new.block, "$bounds $widths")
            assertEquals(old.third, new.slots, "$bounds $widths")
            compared++
        }
        assertTrue(compared > 10_000)
    }

    @Test
    fun `the lounge exit button matches the hand-written position`() {
        (1..600).forEach { height ->
            assertEquals(UiRect(8, (height - 56).coerceAtLeast(8), 104, 20), PvpLoungeExitButtonLayout.bounds(height), "$height")
        }
    }
}

private inline fun <T> legacy(block: () -> T): T? = try {
    block()
} catch (_: IllegalArgumentException) {
    null
}

// ---- The hand-written layouts as they were before the layout tree, kept only to compare against. ----

private fun legacyFrame(bounds: UiRect): Triple<UiRect, UiRect, UiRect> {
    val strip = UiRect(bounds.x, bounds.y, bounds.width, 20)
    val footer = UiRect(bounds.x, bounds.bottom - 30, bounds.width, 30)
    val body = UiRect(bounds.x, strip.bottom + 3, bounds.width, (footer.y - 3 - strip.bottom - 3).coerceAtLeast(1))
    return Triple(strip, body, footer)
}

private object LegacySpectators {
    fun calculate(bounds: UiRect, nameWidths: List<Int>): Triple<List<Int>, UiRect, List<PvpSpectatorSlotLayout>> {
        val rows = (bounds.height / 12).coerceIn(1, 10).coerceAtMost(nameWidths.size)
        val columns = (nameWidths.size + rows - 1) / rows
        val desiredCellWidth = (nameWidths.max() + 10 + 3).coerceAtLeast(44)
        val maximumCellWidth = ((bounds.width - 6 * (columns - 1)) / columns).coerceAtLeast(1)
        val cellWidth = desiredCellWidth.coerceAtMost(maximumCellWidth)
        val blockWidth = cellWidth * columns + 6 * (columns - 1)
        val block = UiRect(bounds.x + (bounds.width - blockWidth) / 2, bounds.y, blockWidth, rows * 12)
        val slots = nameWidths.indices.map { index ->
            val cell = UiRect(block.x + (index / rows) * (cellWidth + 6), block.y + (index % rows) * 12, cellWidth, 12)
            val face = UiRect(cell.x, cell.y + 1, 10, 10)
            PvpSpectatorSlotLayout(index, cell, face, face.right + 3, (cell.right - face.right - 3).coerceAtLeast(1))
        }
        return Triple(listOf(rows, columns), block, slots)
    }
}

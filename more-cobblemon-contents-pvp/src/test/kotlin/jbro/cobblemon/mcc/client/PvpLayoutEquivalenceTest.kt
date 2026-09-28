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
    fun `the room HUD matches the hand-written layout`() {
        var compared = 0
        for (width in 1..900 step 3) for (height in 1..500 step 3) for (expanded in listOf(false, true)) for (spectators in listOf(0, 3, 6, 9)) {
            val old = LegacyHud.calculate(width, height, expanded, spectators) ?: continue
            assertEquals(old, PvpRoomHudLayout.calculate(width, height, expanded, spectators), "$width x $height $expanded $spectators")
            compared++
        }
        assertTrue(compared > 100_000)
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

private object LegacyHud {
    fun calculate(screenWidth: Int, screenHeight: Int, expanded: Boolean, spectatorCount: Int): PvpRoomHudLayout? {
        val visibleSpectators = if (expanded) spectatorCount.coerceAtMost(6) else 0
        val hiddenSpectators = if (expanded) (spectatorCount - visibleSpectators).coerceAtLeast(0) else 0
        val extraRow = if (hiddenSpectators > 0) 1 else 0
        val panelWidth = (if (expanded) 184 else 150).coerceAtMost((screenWidth - 12).coerceAtLeast(1))
        val panelHeight = if (expanded) 18 + 2 + 10 + 22 + 10 + (visibleSpectators + extraRow) * 10 + 5 else 18
        val panel = rect((screenWidth - panelWidth - 6).coerceAtLeast(0), (screenHeight - 28 - panelHeight).coerceAtLeast(6), panelWidth, panelHeight)
            ?: return null
        val header = rect(panel.x, panel.y, panel.width, 18) ?: return null
        val toggle = rect(header.right - 32, header.y, 32, header.height) ?: return null
        val open = rect(toggle.x - 2 - 64, header.y, 64, header.height) ?: return null
        val title = rect(header.x + 4, header.y, (open.x - header.x - 6).coerceAtLeast(1), header.height) ?: return null
        if (!expanded) {
            return PvpRoomHudLayout(panel, header, title, open, toggle, null, UiRect(0, 0, 0, 0), UiRect(0, 0, 0, 0), null, emptyList(), 0, "+")
        }
        val contentLeft = panel.x + 5
        val contentWidth = (panel.width - 10).coerceAtLeast(2)
        val phase = rect(contentLeft, header.bottom + 2, contentWidth, 10) ?: return null
        val leftWidth = (contentWidth - 4) / 2
        val left = rect(contentLeft, phase.bottom, leftWidth, 22) ?: return null
        val right = rect(left.right + 4, phase.bottom, contentWidth - leftWidth - 4, 22) ?: return null
        val heading = rect(contentLeft, left.bottom, contentWidth, 10) ?: return null
        val rows = List(visibleSpectators) { index -> UiRect(contentLeft, heading.bottom + index * 10, contentWidth, 10) }
        return PvpRoomHudLayout(panel, header, title, open, toggle, phase, left, right, heading, rows, hiddenSpectators, "-")
    }

    /** The old HUD used rectangles that allowed negative sizes; those sizes are skipped here. */
    private fun rect(x: Int, y: Int, width: Int, height: Int): UiRect? = if (width < 0 || height < 0) null else UiRect(x, y, width, height)
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

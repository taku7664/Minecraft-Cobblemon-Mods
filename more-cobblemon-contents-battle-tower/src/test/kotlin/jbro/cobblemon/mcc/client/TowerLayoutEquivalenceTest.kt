package jbro.cobblemon.mcc.client

import jbro.cobblemon.uikit.UiRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The Tower tab's layout on the UI kit's layout tree against the hand-written arithmetic it replaced. */
class TowerLayoutEquivalenceTest {
    @Test
    fun `the tower layout matches the hand-written layout`() {
        var compared = 0
        for (width in 1..760 step 3) for (height in 1..480 step 3) {
            val bounds = UiRect(13, 7, width, height)
            val old = legacy(bounds) ?: continue
            val new = TowerHubLayout.calculate(bounds)
            assertEquals(old, listOf(new.strip, new.party, new.setup, new.footer), bounds.toString())
            compared++
        }
        assertTrue(compared > 1_000)
    }

    /** The layout as it was before the layout tree, or null where it could not build its rectangles. */
    private fun legacy(bounds: UiRect): List<UiRect>? = try {
        val strip = UiRect(bounds.x, bounds.y, bounds.width, 20)
        val footer = UiRect(bounds.x, bounds.bottom - 30, bounds.width, 30)
        val body = UiRect(bounds.x, strip.bottom + 3, bounds.width, (footer.y - 3 - strip.bottom - 3).coerceAtLeast(1))
        val room = body.width - 3
        val partyWidth = room * 58 / 100
        val party = UiRect(body.x, body.y, partyWidth.coerceAtLeast(1), body.height)
        val setupX = body.x + partyWidth + 3
        val setup = UiRect(setupX, body.y, (body.right - setupX).coerceAtLeast(1), body.height)
        listOf(strip, party, setup, footer)
    } catch (_: IllegalArgumentException) {
        null
    }
}

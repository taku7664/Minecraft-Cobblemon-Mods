package jbro.cobblemon.uikit

import jbro.cobblemon.uikit.client.CobblemonUiListRows
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** List row actions moved onto the layout tree; they must land where the hand-written arithmetic put them. */
class ListRowLayoutEquivalenceTest {
    @Test
    fun `row actions keep their hand-written positions`() {
        for (width in 40..400 step 7) for (height in 6..40) for (count in 0..3) {
            val bounds = UiRect(11, 5, width, height)
            val size = (height - 6).coerceAtMost(18)
            val old = (0 until count).map { index ->
                UiRect(bounds.right - 4 - (count - index) * (size + 2) + 2, bounds.y + (height - size) / 2, size, size)
            }
            assertEquals(old, CobblemonUiListRows.actionBounds(bounds, count), "$bounds $count")
        }
    }
}

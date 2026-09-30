package jbro.cobblemon.battleui.extended

import jbro.cobblemon.battleui.extended.ui.champions.ChampionsInfoLayout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChampionsInfoLayoutTest {
    @Test
    fun `the window fits a GUI scale 2 screen at its own scale`() {
        val layout = ChampionsInfoLayout.calculate(427, 240)
        val window = layout.window
        assertTrue(window.x() >= 0 && window.x() + window.width() <= 427)
        assertTrue(window.y() >= 0 && window.y() + window.height() <= 240)
        // Three columns side by side inside the window, the field column narrower than the sides.
        assertTrue(layout.ally.x() + layout.ally.width() < layout.field.x())
        assertTrue(layout.field.x() + layout.field.width() < layout.opponent.x())
        assertTrue(layout.opponent.x() + layout.opponent.width() <= window.x() + window.width())
        assertTrue(layout.field.width() < layout.ally.width())
        assertEquals(layout.ally.width(), layout.opponent.width(), 1.0)
        // Wide enough for a name, a level and two stat columns at the GUI's own text size.
        assertTrue(layout.ally.width() >= 130)
    }

    @Test
    fun `a large screen gets a wider window, not a scaled one`() {
        val small = ChampionsInfoLayout.calculate(427, 240).window
        val large = ChampionsInfoLayout.calculate(960, 540).window
        assertTrue(large.width() > small.width())
        assertEquals(ChampionsInfoLayout.MAX_WIDTH, large.width())
        assertEquals((960 - large.width()) / 2, large.x())
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Double) =
        assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "$expected vs $actual")
}

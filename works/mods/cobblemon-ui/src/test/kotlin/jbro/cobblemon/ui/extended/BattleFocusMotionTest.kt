package jbro.cobblemon.ui.extended

import jbro.cobblemon.ui.extended.ui.shared.BattleControlRenderer
import jbro.cobblemon.ui.extended.ui.shared.BattleFocusMotion
import jbro.cobblemon.ui.extended.ui.shared.BattleUiTheme
import jbro.cobblemon.ui.navigation.BattleScreenGeometry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattleFocusMotionTest {
    @Test
    fun `emphasis eases toward its target instead of snapping`() {
        val after40ms = BattleFocusMotion.step(0.0, 1.0, 0.04)
        assertTrue(after40ms > 0.3 && after40ms < 0.5, "partway after one short step: $after40ms")
        assertTrue(BattleFocusMotion.step(0.0, 1.0, 0.25) > 0.9)
        assertEquals(0.4, BattleFocusMotion.step(0.4, 1.0, 0.0))
        // Leaving focus eases out the same way.
        assertTrue(BattleFocusMotion.step(1.0, 0.0, 0.04) in 0.5..0.7)
    }

    @Test
    fun `a focused tile pops past its slide and settles on it`() {
        assertEquals(0f, BattleFocusMotion.overshoot(0f), 1e-6f)
        assertEquals(1f, BattleFocusMotion.overshoot(1f), 1e-6f)
        assertTrue((0..100).map { BattleFocusMotion.overshoot(it / 100f) }.max() > 1.05f)
        assertEquals(0, BattleControlRenderer.protrusion(0f))
        assertEquals(BattleScreenGeometry.FOCUS_PROTRUSION, BattleControlRenderer.protrusion(1f))
    }

    @Test
    fun `a new cursor starts at its target`() {
        assertEquals(42f, BattleFocusMotion.cursor(Any(), 42f))
    }

    @Test
    fun `battle surfaces are rounded unless a surface asks for a cut`() {
        assertTrue(BattleUiTheme.shell.rounded)
        assertTrue(BattleUiTheme.panel.rounded)
        assertTrue(BattleUiTheme.modalBackdrop.rounded)
    }
}

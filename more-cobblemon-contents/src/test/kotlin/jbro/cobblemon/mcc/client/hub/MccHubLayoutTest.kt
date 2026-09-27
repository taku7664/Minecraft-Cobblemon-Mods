package jbro.cobblemon.mcc.client.hub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MccHubLayoutTest {
    @Test
    fun `the rail keeps full height tabs while every tab fits`() {
        val layout = MccHubLayout.calculate(427, 240, tabCount = 6)

        assertFalse(layout.compactTabs)
        assertEquals(6, layout.visibleTabCount())
    }

    @Test
    fun `a rail with more tabs than fit shrinks them so none drops off`() {
        val layout = MccHubLayout.calculate(427, 240, tabCount = 7)

        assertTrue(layout.compactTabs)
        assertTrue(layout.visibleTabCount() >= 7)
        assertTrue(layout.tabButton(6).bottom <= layout.rail.bottom)
    }

    @Test
    fun `a tall screen keeps full height tabs for the same count`() {
        assertFalse(MccHubLayout.calculate(854, 480, tabCount = 7).compactTabs)
    }
}

package jbro.cobblemon.mcc.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpRoomHudLayoutTest {
    @Test
    fun `expanded room hud stays in the lower right above the hotbar`() {
        val layout = PvpRoomHudLayout.calculate(960, 507, expanded = true, spectatorCount = 3)

        assertEquals(954, layout.panel.right)
        assertTrue(layout.panel.bottom <= 507 - PvpRoomHudLayout.HOTBAR_CLEARANCE)
        assertTrue(layout.leftSide.right < layout.rightSide.x)
        assertTrue(layout.spectatorRows.size == 3)
        assertTrue(layout.openButton.right <= layout.toggleButton.x)
    }

    @Test
    fun `the title bar sits inside the window frame and holds its controls`() {
        for (expanded in listOf(false, true)) {
            val layout = PvpRoomHudLayout.calculate(854, 480, expanded, spectatorCount = 2)
            assertTrue(layout.titleBar.x > layout.panel.x && layout.titleBar.right < layout.panel.right)
            assertTrue(layout.titleBar.y > layout.panel.y && layout.titleBar.bottom < layout.header.bottom + 1)
            listOf(layout.title, layout.openButton, layout.toggleButton).forEach { control ->
                assertTrue(control.x >= layout.titleBar.x && control.right <= layout.titleBar.right, "$control in ${layout.titleBar}")
                assertTrue(control.y >= layout.titleBar.y && control.bottom <= layout.titleBar.bottom, "$control in ${layout.titleBar}")
            }
        }
    }

    @Test
    fun `each side card has room for its label, its rule and the name`() {
        val layout = PvpRoomHudLayout.calculate(854, 480, expanded = true, spectatorCount = 0)
        assertEquals(28, layout.leftSide.height)
        assertEquals(layout.leftSide.height, layout.rightSide.height)
        assertTrue(layout.leftSide.bottom <= layout.spectatorHeading!!.y)
    }

    @Test
    fun `collapsed room hud leaves only the plus tab and open gui action`() {
        val layout = PvpRoomHudLayout.calculate(320, 240, expanded = false, spectatorCount = 20)

        assertTrue(layout.panel.width <= 150)
        assertEquals(0, layout.spectatorRows.size)
        assertEquals(layout.panel, layout.header)
        assertTrue(layout.openButton.width >= 42)
        assertEquals("+", layout.toggleLabel)
    }

    @Test
    fun `expanded spectator list is bounded and reports remaining viewers`() {
        val layout = PvpRoomHudLayout.calculate(854, 480, expanded = true, spectatorCount = 10)

        assertEquals(PvpRoomHudLayout.MAX_VISIBLE_SPECTATORS, layout.spectatorRows.size)
        assertEquals(10 - PvpRoomHudLayout.MAX_VISIBLE_SPECTATORS, layout.hiddenSpectatorCount)
        assertEquals("-", layout.toggleLabel)
    }
}

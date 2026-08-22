package jbro.cobblemon.morebattlecontent.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpRoomHudLayoutTest {
    @Test
    fun `expanded room hud stays in the lower right above the hotbar`() {
        val layout = PvpRoomHudLayout.calculate(960, 507, expanded = true, spectatorCount = 3)

        assertEquals(954, layout.panel.right)
        assertTrue(layout.panel.bottom <= 507 - PvpRoomHudLayout.HOTBAR_CLEARANCE)
        assertTrue(layout.leftSide.right < layout.rightSide.left)
        assertTrue(layout.spectatorRows.size == 3)
        assertTrue(layout.openButton.right <= layout.toggleButton.left)
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

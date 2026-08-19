package jbro.cobblemon.morebattlecontent.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpRoomLayoutTest {
    @Test
    fun `minimum room keeps header grouped settings three columns and management footer separate`() {
        val layout = PvpRoomLayout.calculate(320, 240)

        assertTrue(layout.shell.contains(layout.header))
        assertTrue(layout.shell.contains(layout.settings))
        assertTrue(layout.shell.contains(layout.leftSeat))
        assertTrue(layout.shell.contains(layout.spectators))
        assertTrue(layout.shell.contains(layout.rightSeat))
        assertTrue(layout.shell.contains(layout.footer))
        assertTrue(layout.header.contains(layout.closeButton))
        assertTrue(layout.header.bottom < layout.settings.top)
        assertTrue(layout.settings.bottom < layout.leftSeat.top)
        assertTrue(layout.leftSeat.right < layout.spectators.left)
        assertTrue(layout.spectators.right < layout.rightSeat.left)
        assertTrue(layout.leftSeat.bottom < layout.footer.top)
        assertEquals(2, layout.visibilityButtons().size)
        assertEquals(2, layout.formatButtons().size)
        assertEquals(4, layout.mechanicButtons().size)
        assertTrue(layout.visibilityGroup.right < layout.formatGroup.left)
        assertTrue(layout.formatGroup.right < layout.mechanicsGroup.left)
        assertEquals(4, layout.managementButtons().size)
    }

    @Test
    fun `spectator join action belongs to center column rather than management footer`() {
        val layout = PvpRoomLayout.calculate(844, 470)

        assertTrue(layout.spectators.contains(layout.spectatorJoinButton))
        assertTrue(layout.spectatorGrid.bottom < layout.spectatorJoinButton.top)
        assertTrue(layout.managementButtons().none { it == layout.spectatorJoinButton })
    }

    @Test
    fun `room fits the logical size of the latest 844 by 470 capture at gui scale two`() {
        val layout = PvpRoomLayout.calculate(422, 235)

        assertTrue(layout.shell.contains(layout.footer))
        assertTrue(layout.spectatorGrid.height >= 48)
    }
}

private fun TowerPlayRect.contains(other: TowerPlayRect): Boolean =
    other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

package jbro.cobblemon.morebattlecontent.client

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpRoomListLayoutTest {
    @Test
    fun `room count and refresh button share the list header without overlap`() {
        val layout = PvpRoomListLayout.calculate(MbcContentFrameLayout.calculate(854, 480).content)

        assertTrue(layout.listPanel.contains(layout.refreshButton))
        assertTrue(layout.summaryRight < layout.refreshButton.left)
        assertTrue(layout.refreshButton.width >= 64)
        assertTrue(layout.refreshButton.height >= 18)
    }

    @Test
    fun `refresh button remains inside the minimum supported room list`() {
        val layout = PvpRoomListLayout.calculate(MbcContentFrameLayout.calculate(320, 240).content)

        assertTrue(layout.listPanel.contains(layout.refreshButton))
        assertTrue(layout.summaryRight < layout.refreshButton.left)
    }
}

private fun TowerPlayRect.contains(other: TowerPlayRect): Boolean =
    other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

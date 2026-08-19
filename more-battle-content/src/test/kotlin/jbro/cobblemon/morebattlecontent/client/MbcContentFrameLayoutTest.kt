package jbro.cobblemon.morebattlecontent.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MbcContentFrameLayoutTest {
    @Test
    fun `all tab contents share one fixed header tab strip and body`() {
        val frame = MbcContentFrameLayout.calculate(844, 470)
        val tower = TowerPlayLayout.calculate(frame.content)
        val factory = FactoryPlayLayout.calculate(frame.content)
        val rooms = PvpRoomListLayout.calculate(frame.content)

        assertTrue(frame.shell.contains(frame.header))
        assertTrue(frame.shell.contains(frame.tabs))
        assertTrue(frame.shell.contains(frame.content))
        assertTrue(frame.header.contains(frame.closeButton))
        assertTrue(frame.header.bottom < frame.tabs.top)
        assertTrue(frame.tabs.bottom < frame.content.top)
        assertEquals(frame.content, tower.shell)
        assertEquals(frame.content, factory.shell)
        assertEquals(frame.content, rooms.shell)
    }

    @Test
    fun `logical dimensions from latest capture keep common chrome stable`() {
        val frame = MbcContentFrameLayout.calculate(422, 235)

        assertEquals(22, frame.header.height)
        assertEquals(22, frame.tabs.height)
        assertTrue(frame.content.height >= 145)
    }
}

private fun TowerPlayRect.contains(other: TowerPlayRect): Boolean =
    other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

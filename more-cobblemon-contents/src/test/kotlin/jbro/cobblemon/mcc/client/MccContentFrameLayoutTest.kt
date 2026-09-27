package jbro.cobblemon.mcc.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MccContentFrameLayoutTest {
    @Test
    fun `all tab contents share one fixed header tab strip and body`() {
        val frame = MccContentFrameLayout.calculate(844, 470)

        assertTrue(frame.shell.contains(frame.header))
        assertTrue(frame.shell.contains(frame.tabs))
        assertTrue(frame.shell.contains(frame.content))
        assertTrue(frame.header.contains(frame.closeButton))
        assertTrue(frame.header.contains(frame.helpButton))
        assertTrue(frame.helpButton.right < frame.closeButton.left)
        assertTrue(frame.header.bottom < frame.tabs.top)
        assertTrue(frame.tabs.bottom < frame.content.top)
    }

    @Test
    fun `logical dimensions from latest capture keep common chrome stable`() {
        val frame = MccContentFrameLayout.calculate(422, 235)

        assertEquals(22, frame.header.height)
        assertEquals(22, frame.tabs.height)
        assertEquals(frame.closeButton.width, frame.helpButton.width)
        assertEquals(4, frame.closeButton.left - frame.helpButton.right)
        assertTrue(frame.content.height >= 145)
    }
}

private fun MccRect.contains(other: MccRect): Boolean =
    other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

package jbro.cobblemon.morebattlecontent.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpLoungeExitButtonLayoutTest {
    @Test
    fun `MBC return button stays inside the lower left of the battle screen`() {
        val screenHeight = 240
        val button = PvpLoungeExitButtonLayout.bounds(screenHeight)

        assertEquals(8, button.left)
        assertEquals(104, button.width)
        assertEquals(20, button.height)
        assertTrue(button.top >= 8)
        assertTrue(button.bottom <= screenHeight)
    }
}

package jbro.cobblemon.morebattlecontent.client

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpLoungeExitButtonLayoutTest {
    @Test
    fun `custom return button stays above the cobblemon spectator back button`() {
        val screenHeight = 240
        val custom = PvpLoungeExitButtonLayout.bounds(screenHeight)
        val cobblemon = TowerPlayRect(12, screenHeight - 32, 29, 17)

        assertFalse(custom.overlaps(cobblemon))
        assertTrue(custom.left <= 12)
        assertTrue(custom.bottom <= cobblemon.top)
    }

    private fun TowerPlayRect.overlaps(other: TowerPlayRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}

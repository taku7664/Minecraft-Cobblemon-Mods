package jbro.cobblemon.morebattlecontent.client

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShopScreenLayoutTest {
    @Test
    fun `home keeps full height character leaderboard and shop in three fixed columns`() {
        val frame = MbcContentFrameLayout.calculate(844, 470)
        val layout = ShopScreenLayout.calculate(frame.content)

        assertTrue(frame.content.contains(layout.characterPanel))
        assertTrue(frame.content.contains(layout.leaderboardPanel))
        assertTrue(frame.content.contains(layout.shopPanel))
        assertFalse(layout.characterPanel.overlaps(layout.leaderboardPanel))
        assertFalse(layout.characterPanel.overlaps(layout.shopPanel))
        assertFalse(layout.leaderboardPanel.overlaps(layout.shopPanel))
        assertTrue(layout.characterPanel.right < layout.leaderboardPanel.left)
        assertTrue(layout.leaderboardPanel.right < layout.shopPanel.left)
        assertEquals(layout.characterPanel.top, layout.leaderboardPanel.top)
        assertEquals(layout.characterPanel.bottom, layout.leaderboardPanel.bottom)
        assertEquals(layout.characterPanel.top, layout.shopPanel.top)
        assertEquals(layout.characterPanel.bottom, layout.shopPanel.bottom)
        assertTrue(kotlin.math.abs(layout.characterPanel.width * 3 - layout.leaderboardPanel.width * 2) <= 2)
        assertTrue(layout.leaderboardPanel.width * 100 in layout.shell.width * 34..layout.shell.width * 37)
        assertTrue(layout.shopPanel.width * 100 < layout.shell.width * 45)
        assertEquals(layout.characterPanel.top + 4, layout.characterViewport.top)
        assertTrue(layout.characterViewport.height > layout.characterPanel.height * 4 / 5)
        assertTrue(layout.shopPanel.contains(layout.shopBalanceBadge))
        assertTrue(layout.shopPanel.contains(layout.shopViewport))
        assertTrue(layout.shopPanel.contains(layout.shopScrollTrack))
        assertFalse(layout.shopViewport.overlaps(layout.shopScrollTrack))
        assertTrue(layout.leaderboardPanel.contains(layout.leaderboardViewport))
        assertTrue(layout.leaderboardPanel.contains(layout.leaderboardScrollTrack))
        assertFalse(layout.leaderboardViewport.overlaps(layout.leaderboardScrollTrack))
        assertEquals(3, layout.leaderboardContentButtons.size)
        assertEquals(2, layout.leaderboardFormatButtons.size)
        assertEquals(2, layout.leaderboardLevelButtons.size)
        layout.leaderboardContentButtons.forEach { assertTrue(layout.leaderboardPanel.contains(it)) }
        layout.leaderboardFormatButtons.forEach { assertTrue(layout.leaderboardPanel.contains(it)) }
        layout.leaderboardLevelButtons.forEach { assertTrue(layout.leaderboardPanel.contains(it)) }
        assertTrue(layout.leaderboardLevelButtons.last().bottom < layout.leaderboardViewport.top)
    }

    @Test
    fun `small home preserves the same three column information hierarchy`() {
        val frame = MbcContentFrameLayout.calculate(320, 240)
        val layout = ShopScreenLayout.calculate(frame.content)

        assertTrue(frame.content.contains(layout.characterPanel))
        assertTrue(frame.content.contains(layout.leaderboardPanel))
        assertTrue(frame.content.contains(layout.shopPanel))
        assertFalse(layout.characterPanel.overlaps(layout.leaderboardPanel))
        assertFalse(layout.characterPanel.overlaps(layout.shopPanel))
        assertFalse(layout.leaderboardPanel.overlaps(layout.shopPanel))
        assertTrue(layout.characterPanel.right < layout.leaderboardPanel.left)
        assertTrue(layout.leaderboardPanel.right < layout.shopPanel.left)
        assertEquals(layout.characterPanel.height, layout.leaderboardPanel.height)
        assertEquals(layout.characterPanel.height, layout.shopPanel.height)
        assertTrue(layout.characterPanel.width < layout.leaderboardPanel.width)
        assertTrue(layout.leaderboardPanel.width <= layout.shopPanel.width)
        assertTrue(layout.characterViewport.height > layout.characterPanel.height * 4 / 5)
        assertTrue(layout.shopPanel.contains(layout.shopBalanceBadge))
        assertEquals(3, layout.leaderboardContentButtons.size)
        assertEquals(2, layout.leaderboardFormatButtons.size)
        assertEquals(2, layout.leaderboardLevelButtons.size)
    }

    @Test
    fun `shop cards advance vertically and share one vertical offset`() {
        val frame = MbcContentFrameLayout.calculate(844, 470)
        val layout = ShopScreenLayout.calculate(frame.content)
        val first = layout.shopCardBounds(0, scrollOffset = 17)
        val second = layout.shopCardBounds(1, scrollOffset = 17)

        assertTrue(second.top > first.top)
        org.junit.jupiter.api.Assertions.assertEquals(first.left, second.left)
        org.junit.jupiter.api.Assertions.assertEquals(layout.shopViewport.top - 17, first.top)
    }

    @Test
    fun `single product purchase uses the full footer width without a clear button slot`() {
        listOf(
            ShopScreenLayout.calculate(MbcContentFrameLayout.calculate(844, 470).content),
            ShopScreenLayout.calculate(MbcContentFrameLayout.calculate(320, 240).content),
        ).forEach { layout ->
            assertEquals(layout.shopPanel.left + 4, layout.purchase.left)
            assertEquals(layout.shopPanel.right - 4, layout.purchase.right)
            assertTrue(layout.decrement.bottom < layout.purchase.top)
            assertTrue(layout.increment.bottom < layout.purchase.top)
        }
    }
}

private fun TowerPlayRect.contains(other: TowerPlayRect): Boolean =
    other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

private fun TowerPlayRect.overlaps(other: TowerPlayRect): Boolean =
    left < other.right && right > other.left && top < other.bottom && bottom > other.top

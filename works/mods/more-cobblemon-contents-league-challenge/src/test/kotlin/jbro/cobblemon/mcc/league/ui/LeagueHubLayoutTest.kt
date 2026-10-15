package jbro.cobblemon.mcc.league.ui

import jbro.cobblemon.mcc.client.hub.MccHubKit
import jbro.cobblemon.uikit.UiRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LeagueHubLayoutTest {
    /** The 427x240 hub's content area first: an 854x480 window at GUI scale 2. */
    private val sizes = listOf(UiRect(110, 46, 305, 184), UiRect(0, 0, 420, 260), UiRect(40, 20, 590, 340))

    @Test fun `tabs open into the page and cards and footer follow it in order`() {
        sizes.forEach { bounds ->
            val layout = LeagueHubLayout.calculate(bounds)
            assertEquals(bounds.y, layout.tabs.y, "$bounds")
            assertEquals(layout.tabs.bottom, layout.page.y, "$bounds")
            assertTrue(layout.page.bottom < layout.challenger.y, "$bounds")
            assertTrue(layout.challenger.right < layout.mechanics.x && layout.mechanics.right < layout.rewards.x, "$bounds")
            assertEquals(bounds.right, layout.rewards.right, "$bounds")
            assertTrue(layout.challenger.bottom < layout.footer.y, "$bounds")
            assertEquals(bounds.bottom, layout.footer.bottom, "$bounds")
            assertTrue(layout.captions, "$bounds")
        }
    }

    @Test fun `the ordinary hub fits five whole gimmick rows and all nine stages`() {
        val layout = LeagueHubLayout.calculate(sizes.first())
        assertTrue(layout.mechanics.height - MccHubKit.CARD_CHROME_HEIGHT >= 45, "mechanics ${layout.mechanics}")
        assertTrue(layout.routeCapacity >= 9, "capacity ${layout.routeCapacity}")
    }

    @Test fun `a larger hub gives the cards the extra height`() {
        val small = LeagueHubLayout.calculate(sizes.first())
        val large = LeagueHubLayout.calculate(sizes.last())
        assertEquals(small.page.height, large.page.height)
        assertTrue(large.challenger.height > small.challenger.height)
    }
}

package jbro.cobblemon.mcc.league.ui

import jbro.cobblemon.uikit.UiRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LeagueHubLayoutTest {
    private val sizes = listOf(UiRect(110, 46, 305, 184), UiRect(0, 0, 420, 260), UiRect(40, 20, 590, 340))

    @Test fun `every hub size keeps summary route cards and footer distinct`() {
        sizes.forEach { bounds ->
            val layout = LeagueHubLayout.calculate(bounds)
            assertTrue(layout.summary.bottom <= layout.route.y, "$bounds")
            assertTrue(layout.route.bottom <= layout.detail.y, "$bounds")
            assertTrue(layout.detail.right < layout.mechanics.x && layout.mechanics.right < layout.status.x, "$bounds")
            assertTrue(layout.detail.bottom <= layout.footer.y, "$bounds")
            assertEquals(bounds.right, layout.status.right, "$bounds")
            assertEquals(bounds.x, layout.panel.x)
            assertEquals(bounds.right, layout.panel.right)
            assertEquals(layout.body.bottom, layout.panel.bottom)
            assertTrue(layout.panel.bottom < layout.footer.y)
            assertEquals(26, layout.footer.height)
            assertEquals(bounds.bottom, layout.footer.bottom, "$bounds")
        }
    }

    @Test fun `smallest hub content area still fits the compact challenge card`() {
        val layout = LeagueHubLayout.calculate(sizes.first())
        // Five compact mechanic rows and a title fit within the same body as the portrait and rewards.
        assertTrue(layout.detail.height >= 65, "detail height ${layout.detail.height}")
    }

    @Test fun `ordinary game width shows all nine stages without route paging`() {
        assertTrue(LeagueHubLayout.calculate(sizes.first()).routeCapacity >= 9)
    }

    @Test fun `restyled lower cards preserve the approved stage map rectangle`() {
        assertEquals(UiRect(116, 72, 293, 56), LeagueHubLayout.calculate(sizes.first()).route)
    }

    @Test fun `route viewport keeps names apart and reserves gutters on small screens`() {
        sizes.forEach { bounds ->
            val layout = LeagueHubLayout.calculate(bounds)
            val count = layout.routeCapacity.coerceAtMost(9)
            val centers = layout.routeCenters(count)
            assertEquals(count, centers.size)
            assertTrue(centers.first() - 15 >= layout.route.x)
            assertTrue(centers.last() + 15 <= layout.route.right)
            assertTrue(centers.zipWithNext().all { (a, b) -> b - a >= 30 })
        }
    }

    @Test fun `larger hub gives the cards the extra height`() {
        val small = LeagueHubLayout.calculate(sizes.first())
        val large = LeagueHubLayout.calculate(sizes.last())
        assertEquals(small.route.height, large.route.height)
        assertTrue(large.detail.height > small.detail.height)
    }
}

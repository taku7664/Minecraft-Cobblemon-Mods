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
            assertTrue(layout.detail.right < layout.status.x, "$bounds")
            assertTrue(layout.detail.bottom <= layout.footer.y, "$bounds")
            assertEquals(bounds.right, layout.status.right, "$bounds")
            assertEquals(bounds.bottom, layout.footer.bottom, "$bounds")
        }
    }

    @Test fun `smallest hub content area still fits the compact challenge card`() {
        val layout = LeagueHubLayout.calculate(sizes.first())
        // The compact challenge card needs 86 rows for its badge, name, state and reward lines.
        assertTrue(layout.detail.height >= 86, "detail height ${layout.detail.height}")
    }

    @Test fun `nine route nodes stay inside the route and keep room for their badge boxes`() {
        sizes.forEach { bounds ->
            val layout = LeagueHubLayout.calculate(bounds)
            val centers = layout.routeCenters(9)
            assertEquals(9, centers.size)
            assertTrue(centers.first() - 14 >= layout.route.x)
            assertTrue(centers.last() + 14 <= layout.route.right)
            assertTrue(centers.zipWithNext().all { (a, b) -> b - a >= 28 })
        }
    }

    @Test fun `larger hub gives the cards the extra height`() {
        val small = LeagueHubLayout.calculate(sizes.first())
        val large = LeagueHubLayout.calculate(sizes.last())
        assertEquals(small.route.height, large.route.height)
        assertTrue(large.detail.height > small.detail.height)
    }
}

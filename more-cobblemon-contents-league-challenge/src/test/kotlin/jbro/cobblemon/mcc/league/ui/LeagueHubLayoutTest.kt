package jbro.cobblemon.mcc.league.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LeagueHubLayoutTest {
    @Test fun `smallest hub content area keeps summary route cards and footer distinct`() {
        val layout = LeagueHubLayout.calculate(110, 46, 305, 184)
        assertTrue(layout.summary.bottom <= layout.route.top)
        assertTrue(layout.route.bottom <= layout.detail.top)
        assertTrue(layout.detail.right < layout.status.left)
        assertTrue(layout.detail.bottom <= layout.footer.top)
        assertEquals(46 + 184, layout.footer.bottom)
        // The compact challenge card needs 86 rows for its badge, name, state and reward lines.
        assertTrue(layout.detail.height >= 86, "detail height ${layout.detail.height}")
    }

    @Test fun `nine route nodes stay inside the route and keep room for their badge boxes`() {
        val layout = LeagueHubLayout.calculate(110, 46, 305, 184)
        val centers = layout.routeCenters(9)
        assertEquals(9, centers.size)
        assertTrue(centers.first() - 14 >= layout.route.left)
        assertTrue(centers.last() + 14 <= layout.route.right)
        assertTrue(centers.zipWithNext().all { (a, b) -> b - a >= 28 })
    }

    @Test fun `larger hub gives the cards the extra height`() {
        val small = LeagueHubLayout.calculate(0, 0, 305, 184)
        val large = LeagueHubLayout.calculate(0, 0, 500, 300)
        assertEquals(small.route.height, large.route.height)
        assertTrue(large.detail.height > small.detail.height)
    }
}

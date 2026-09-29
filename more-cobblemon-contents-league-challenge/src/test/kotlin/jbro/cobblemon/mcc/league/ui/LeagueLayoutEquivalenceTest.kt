package jbro.cobblemon.mcc.league.ui

import jbro.cobblemon.uikit.UiRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The League tab's layout on the UI kit's layout tree against the hand-written arithmetic it replaced. */
class LeagueLayoutEquivalenceTest {
    @Test
    fun `the league layout and its route match the hand-written layout`() {
        var compared = 0
        for (width in 1..760 step 3) for (height in 1..480 step 3) {
            val bounds = UiRect(13, 7, width, height)
            val old = legacy(bounds) ?: continue
            val new = LeagueHubLayout.calculate(bounds)
            assertEquals(old, new, bounds.toString())
            (0..10).forEach { count -> assertEquals(legacyCenters(old.route, count), new.routeCenters(count), "$bounds $count") }
            compared++
        }
        assertTrue(compared > 1_000)
    }

    /** The layout as it was before the layout tree, or null where it could not build its rectangles. */
    private fun legacy(bounds: UiRect): LeagueHubLayout? = try {
        val summary = UiRect(bounds.x, bounds.y, bounds.width, 20)
        val route = UiRect(bounds.x, summary.bottom + 3, bounds.width, 38)
        val footer = UiRect(bounds.x, bounds.bottom - 30, bounds.width, 30)
        val body = UiRect(bounds.x, route.bottom + 3, bounds.width, (footer.y - 3 - route.bottom - 3).coerceAtLeast(1))
        val room = body.width - 3
        val detailWidth = room * 55 / 100
        val detail = UiRect(body.x, body.y, detailWidth.coerceAtLeast(1), body.height)
        val statusX = body.x + detailWidth + 3
        val status = UiRect(statusX, body.y, (body.right - statusX).coerceAtLeast(1), body.height)
        LeagueHubLayout(summary, route, detail, status, footer)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun legacyCenters(route: UiRect, count: Int): List<Int> {
        if (count <= 0) return emptyList()
        val first = route.x + 18
        val last = route.right - 18
        if (count == 1) return listOf((first + last) / 2)
        return (0 until count).map { index -> first + (last - first) * index / (count - 1) }
    }
}

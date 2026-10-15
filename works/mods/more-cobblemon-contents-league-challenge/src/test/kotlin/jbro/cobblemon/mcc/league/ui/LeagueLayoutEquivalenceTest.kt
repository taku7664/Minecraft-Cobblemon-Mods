package jbro.cobblemon.mcc.league.ui

import jbro.cobblemon.mcc.client.hub.MccHubLayout
import jbro.cobblemon.mcc.league.network.LeagueChallengeView
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Actual hub areas, from small screens up: every node stays on the page and every stage can be paged to. */
class LeagueLayoutEquivalenceTest {
    private val route = (1..9).flatMap { stage ->
        (if (stage == 4 || stage == 6) 0..1 else 0..0).map { branch ->
            LeagueChallengeView("test:s"+stage+"b"+branch, "name", "AVAILABLE", 80, stage = stage, branch = branch)
        }
    }

    @Test fun branchesStayOnThePageAndEveryStageIsReachable() {
        for (w in 320..900 step 23) for (h in 200..500 step 17) {
            val bounds = MccHubLayout.calculate(w, h, 7).content
            val l = LeagueHubLayout.calculate(bounds)
            val paged = 9 > l.routeCapacity
            val area = l.routeArea(paged)
            val lastStart = if (paged) (9 - l.pagedCapacity()).coerceAtLeast(0) else 0
            val reached = mutableSetOf<String>()
            for (offset in 0..lastStart) {
                val rectangles = l.nodeRects(route, offset, area)
                reached += rectangles.keys
                rectangles.values.forEach { r ->
                    // The two-pixel selection ring around the icon box stays inside the page's one-pixel edge.
                    val box = r.x + (r.width - LeagueHubLayout.BOX) / 2
                    assertTrue(box - 2 >= l.page.x + 1 && box + LeagueHubLayout.BOX + 2 <= l.page.right - 1, "$bounds $r")
                    assertTrue(r.y - 2 >= l.page.y + 1 && r.bottom <= l.page.bottom - 1, "$bounds $r")
                    if (paged) assertTrue(r.x >= area.x && r.right <= area.right, "$bounds $r")
                }
                listOf(4, 6).forEach { stage ->
                    val nodes = route.filter { it.stage == stage && it.id in rectangles }.map { rectangles.getValue(it.id) }
                    if (nodes.isEmpty()) return@forEach
                    assertEquals(nodes[0].x, nodes[1].x)
                    assertTrue(nodes[0].bottom < nodes[1].y)
                }
            }
            assertEquals(route.map { it.id }.toSet(), reached, "Every stage is reachable through the pages: $bounds")
        }
    }
}

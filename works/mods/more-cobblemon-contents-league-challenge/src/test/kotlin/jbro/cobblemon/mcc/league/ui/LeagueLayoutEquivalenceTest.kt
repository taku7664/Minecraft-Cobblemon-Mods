package jbro.cobblemon.mcc.league.ui

import jbro.cobblemon.mcc.client.hub.MccHubLayout
import jbro.cobblemon.mcc.league.network.LeagueChallengeView
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Actual hub areas, including a 320x200 screen rather than an oversized browser mockup. */
class LeagueLayoutEquivalenceTest {
    private val route = (1..9).flatMap { stage ->
        (if (stage == 4 || stage == 6) 0..1 else 0..0).map { branch ->
            LeagueChallengeView("test:s"+stage+"b"+branch, "name", "AVAILABLE", 80, stage = stage, branch = branch)
        }
    }
    @Test fun checklistAndBranchesFitEverySupportedGameSize() {
        for (w in 320..900 step 23) for (h in 200..500 step 17) {
            val bounds = MccHubLayout.calculate(w, h, 7).content
            val l = LeagueHubLayout.calculate(bounds)
            assertTrue(l.summary.bottom <= l.route.y)
            assertTrue(l.route.bottom <= l.body.y)
            assertEquals(l.detail.right, l.mechanics.x)
            assertEquals(l.mechanics.right, l.status.x)
            assertTrue(l.body.bottom <= l.footer.y)
            assertTrue(l.mechanics.height >= 65, bounds.toString())
            val reached = mutableSetOf<String>()
            for (offset in 0..9) {
            val rectangles = l.nodeRects(route, offset)
            reached += rectangles.keys
            rectangles.values.forEach { r ->
                assertTrue(r.x >= l.route.x && r.right <= l.route.right, r.toString())
                assertTrue(r.y >= l.route.y && r.bottom <= l.route.bottom, r.toString())
                assertTrue(r.width >= 30, r.toString())
                // Selection frame, 12px face and 9px name all remain in the node.
                if (r.height >= 24) assertTrue(r.y + 2 + 12 <= r.bottom - 10)
            }
            listOf(4, 6).forEach { stage ->
                val nodes = route.filter { it.stage == stage && it.id in rectangles }.map { rectangles.getValue(it.id) }
                if (nodes.isEmpty()) return@forEach
                assertEquals(nodes[0].x, nodes[1].x)
                assertTrue(nodes[0].bottom < nodes[1].y)
            }
            }
            assertEquals(route.map { it.id }.toSet(), reached, "Every stage is reachable through the viewport")
        }
    }
}

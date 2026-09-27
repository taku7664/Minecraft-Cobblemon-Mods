package jbro.cobblemon.mcc.league.ui

import jbro.cobblemon.mcc.client.hub.MccHubKit
import jbro.cobblemon.uikit.UiRect

/**
 * League inside the MCC hub content area: a summary strip, the challenge route, the challenge and status
 * cards and a footer. The hub already draws the brand, BP and a close button. It lays out any rectangle
 * from the smallest content area (about 305x184 at a 427x240 GUI) up; the cards take the extra room.
 */
data class LeagueHubLayout(
    val summary: UiRect,
    val route: UiRect,
    val detail: UiRect,
    val status: UiRect,
    val footer: UiRect,
) {
    /** Horizontal centers of [count] route nodes, spread across the route with room for the end badges. */
    fun routeCenters(count: Int): List<Int> {
        if (count <= 0) return emptyList()
        val first = route.x + ROUTE_EDGE
        val last = route.right - ROUTE_EDGE
        if (count == 1) return listOf((first + last) / 2)
        return (0 until count).map { index -> first + (last - first) * index / (count - 1) }
    }

    companion object {
        const val ROUTE_HEIGHT = 38
        private const val ROUTE_EDGE = 18
        private const val GAP = MccHubKit.GAP

        fun calculate(bounds: UiRect): LeagueHubLayout {
            require(bounds.width > 0 && bounds.height > 0) { "League hub bounds must be positive" }
            val summary = UiRect(bounds.x, bounds.y, bounds.width, MccHubKit.STRIP_HEIGHT)
            val route = UiRect(bounds.x, summary.bottom + GAP, bounds.width, ROUTE_HEIGHT)
            val footer = UiRect(bounds.x, bounds.bottom - MccHubKit.FOOTER_HEIGHT, bounds.width, MccHubKit.FOOTER_HEIGHT)
            val body = UiRect(bounds.x, route.bottom + GAP, bounds.width,
                (footer.y - GAP - route.bottom - GAP).coerceAtLeast(1))
            val (detail, status) = MccHubKit.columns(body, 55, 45)
            return LeagueHubLayout(summary, route, detail, status, footer)
        }
    }
}

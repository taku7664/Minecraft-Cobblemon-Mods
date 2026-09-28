package jbro.cobblemon.mcc.league.ui

import jbro.cobblemon.mcc.client.hub.MccHubKit
import jbro.cobblemon.uikit.UiLayout
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
        return UiLayout.spread(route.x + ROUTE_EDGE, route.right - ROUTE_EDGE, count)
    }

    companion object {
        const val ROUTE_HEIGHT = 38
        private const val ROUTE_EDGE = 18
        private const val GAP = MccHubKit.GAP

        fun calculate(bounds: UiRect): LeagueHubLayout {
            require(bounds.width > 0 && bounds.height > 0) { "League hub bounds must be positive" }
            val layout = MccHubKit.tabFrame(UiLayout.column(gap = GAP) {
                fixed(ROUTE_HEIGHT, "route")
                weight(UiLayout.row(gap = GAP) {
                    weight("detail", 55, min = 1)
                    weight("status", 45, min = 1)
                }, min = 1)
            }).solve(bounds)
            return LeagueHubLayout(layout["strip"], layout["route"], layout["detail"], layout["status"], layout["footer"])
        }
    }
}

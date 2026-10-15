package jbro.cobblemon.mcc.league.ui

import jbro.cobblemon.mcc.league.network.LeagueChallengeView
import jbro.cobblemon.uikit.UiLayout
import jbro.cobblemon.uikit.UiRect

/** Route geometry stays fixed; three square information cards and the original 26px actions follow it. */
data class LeagueHubLayout(val summary: UiRect, val route: UiRect, val detail: UiRect,
    val status: UiRect, val footer: UiRect, val mechanics: UiRect) {
    val body: UiRect get() = UiRect(detail.x, detail.y, status.right - detail.x, detail.height)
    val panel: UiRect get() = UiRect(summary.x, summary.bottom + 3, summary.width, body.bottom - summary.bottom - 3)
    val mapPanel: UiRect get() = UiRect(summary.x, summary.bottom + 3, summary.width, route.bottom - summary.bottom - 4)
    val routeCapacity: Int get() = (if (route.width >= 270) route.width / 30 else (route.width - 28) / 30).coerceAtLeast(1)
    fun routeCenters(count: Int): List<Int> {
        val gutter = if (routeCapacity < 9) 29 else 15
        return UiLayout.spread(route.x + gutter, route.right - gutter, count)
    }
    fun nodeRects(entries: List<LeagueChallengeView>, firstStage: Int = 0): Map<String, UiRect> {
        val groups = entries.withIndex().groupBy { (index, entry) -> entry.stage.takeIf { it > 0 } ?: (index + 1) }
        val visible = groups.values.drop(firstStage.coerceIn(0, (groups.size - routeCapacity).coerceAtLeast(0))).take(routeCapacity)
        val centers = routeCenters(visible.size)
        val width = 30.coerceAtMost((route.width - 28).coerceAtLeast(1))
        return buildMap {
            visible.forEachIndexed { column, alternatives ->
                val height = minOf(24, (route.height - 6) / 2).coerceAtLeast(1)
                alternatives.forEachIndexed { branch, (_, entry) ->
                    val y = if (alternatives.size > 1) route.y + 2 + branch * (height + 2) else route.y + (route.height - height) / 2
                    put(entry.id, UiRect(centers[column] - width / 2, y, width, height.coerceAtLeast(1)))
                }
            }
        }
    }
    companion object {
        const val ROUTE_HEIGHT = 56
        fun calculate(bounds: UiRect): LeagueHubLayout {
            require(bounds.width > 0 && bounds.height > 0)
            val gap = 3
            val control = minOf(18, (bounds.height / 6).coerceAtLeast(1))
            val routeHeight = minOf(ROUTE_HEIGHT, (bounds.height - control * 2 - gap * 2 - 15 - 65).coerceAtLeast(24))
            val summary = UiRect(bounds.x, bounds.y, bounds.width, control)
            val route = UiRect(bounds.x + 6, summary.bottom + gap + 5, bounds.width - 12, routeHeight)
            val footer = UiRect(bounds.x, bounds.bottom - 26, bounds.width, 26)
            val top = route.bottom + 1
            val height = (footer.y - gap - top).coerceAtLeast(1)
            val room = bounds.width - gap * 2
            val left = room * 30 / 100
            val middle = room * 34 / 100
            return LeagueHubLayout(summary, route,
                UiRect(bounds.x, top, left, height),
                UiRect(bounds.x + left + middle + gap * 2, top, room - left - middle, height), footer,
                UiRect(bounds.x + left + gap, top, middle, height))
        }
    }
}

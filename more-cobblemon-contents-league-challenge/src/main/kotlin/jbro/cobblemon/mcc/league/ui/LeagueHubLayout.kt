package jbro.cobblemon.mcc.league.ui

/** Logical GUI coordinates. Minecraft applies the player's GUI scale afterward. */
data class LeagueDashboardRect(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

/**
 * League inside the MCC hub content area. The hub already draws the brand, BP and a close button, so League
 * keeps a summary strip, the challenge route, the two cards and its own actions. The smallest content area,
 * a 427x240 GUI, is about 305 by 184.
 */
data class LeagueHubLayout(
    val summary: LeagueDashboardRect,
    val route: LeagueDashboardRect,
    val detail: LeagueDashboardRect,
    val status: LeagueDashboardRect,
    val footer: LeagueDashboardRect,
) {
    /** Horizontal centers of [count] route nodes, spread across the route with room for the end badges. */
    fun routeCenters(count: Int): List<Int> {
        if (count <= 0) return emptyList()
        val first = route.left + ROUTE_EDGE
        val last = route.right - ROUTE_EDGE
        if (count == 1) return listOf((first + last) / 2)
        return (0 until count).map { index -> first + (last - first) * index / (count - 1) }
    }

    companion object {
        const val SUMMARY_HEIGHT = 20
        const val ROUTE_HEIGHT = 38

        /** A rule, then a medium Pixel League button (26 rows) with a little room around it. */
        const val FOOTER_HEIGHT = 30
        private const val GAP = 3
        private const val ROUTE_EDGE = 18

        fun calculate(left: Int, top: Int, width: Int, height: Int): LeagueHubLayout {
            require(width > 0 && height > 0) { "League hub bounds must be positive" }
            val summary = LeagueDashboardRect(left, top, width, SUMMARY_HEIGHT)
            val route = LeagueDashboardRect(left, summary.bottom + GAP, width, ROUTE_HEIGHT)
            val footer = LeagueDashboardRect(left, top + height - FOOTER_HEIGHT, width, FOOTER_HEIGHT)
            val bodyTop = route.bottom + GAP
            val bodyHeight = (footer.top - GAP - bodyTop).coerceAtLeast(1)
            val detailWidth = (width * 55 / 100).coerceAtLeast(1)
            val detail = LeagueDashboardRect(left, bodyTop, detailWidth, bodyHeight)
            val status = LeagueDashboardRect(detail.right + GAP, bodyTop, (width - detailWidth - GAP).coerceAtLeast(1), bodyHeight)
            return LeagueHubLayout(summary, route, detail, status, footer)
        }
    }
}

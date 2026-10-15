package jbro.cobblemon.mcc.league.ui

import jbro.cobblemon.mcc.league.network.LeagueChallengeView
import jbro.cobblemon.uikit.UiLayout
import jbro.cobblemon.uikit.UiRect

/**
 * The League tab: region tabs over a route page, three titled cards under it (the challenger, the battle gimmicks,
 * what a win gives) and a footer. Route nodes are an icon box with the trainer's name under it; a stage with two
 * gyms to pick from stacks them.
 */
data class LeagueHubLayout(val tabs: UiRect, val page: UiRect, val route: UiRect, val challenger: UiRect,
    val mechanics: UiRect, val rewards: UiRect, val footer: UiRect,
    /** A short hub drops the names under the nodes; their tooltips still name them. */
    val nodeHeight: Int = NODE_HEIGHT) {
    val captions: Boolean get() = nodeHeight > BOX

    /** Stages the route shows side by side; more page with arrows at the page's edges. */
    val routeCapacity: Int get() = (route.width / STAGE_WIDTH).coerceAtLeast(1)

    /** The route without its paging arrows, when [paged]. */
    fun routeArea(paged: Boolean): UiRect =
        if (!paged) route else UiRect(route.x + ARROW_WIDTH, route.y, (route.width - ARROW_WIDTH * 2).coerceAtLeast(1), route.height)

    /** Stages shown when the route pages: the arrows take room from both ends. */
    fun pagedCapacity(): Int = (routeArea(true).width / STAGE_WIDTH).coerceAtLeast(1)

    /** Node rectangles of the stages from [firstStage] on that fit [area], keyed by challenge id. */
    fun nodeRects(entries: List<LeagueChallengeView>, firstStage: Int = 0, area: UiRect = route): Map<String, UiRect> {
        val groups = entries.withIndex().groupBy { (index, entry) -> entry.stage.takeIf { it > 0 } ?: (index + 1) }.values.toList()
        val capacity = (area.width / STAGE_WIDTH).coerceAtLeast(1)
        val visible = groups.drop(firstStage.coerceIn(0, (groups.size - capacity).coerceAtLeast(0))).take(capacity)
        val step = area.width / visible.size.coerceAtLeast(1)
        return buildMap {
            visible.forEachIndexed { column, alternatives ->
                val left = area.x + column * step + (step - NODE_WIDTH) / 2
                val rows = alternatives.size
                val total = rows * nodeHeight + (rows - 1) * BRANCH_GAP
                val top = area.y + (area.height - total) / 2
                alternatives.forEachIndexed { row, (_, entry) ->
                    put(entry.id, UiRect(left, top + row * (nodeHeight + BRANCH_GAP), NODE_WIDTH, nodeHeight))
                }
            }
        }
    }

    companion object {
        const val TAB_HEIGHT = 15
        const val FOOTER_HEIGHT = 26
        const val GAP = 3
        const val NODE_WIDTH = 30
        const val BOX = 18
        /** The icon box and the name line under it. */
        const val NODE_HEIGHT = BOX + 1 + 9
        const val BRANCH_GAP = 3
        const val STAGE_WIDTH = 33
        const val ARROW_WIDTH = 12
        /** Rows the page keeps around its stacked nodes. */
        const val PAGE_MARGIN = 3
        /** The smallest content height that still gets node names; the 427x240 hub's area is 184 tall. */
        const val CAPTION_MIN_HEIGHT = 180

        fun pageHeight(nodeHeight: Int) = nodeHeight * 2 + BRANCH_GAP + PAGE_MARGIN * 2

        fun calculate(bounds: UiRect): LeagueHubLayout {
            require(bounds.width > 0 && bounds.height > 0)
            val nodeHeight = if (bounds.height >= CAPTION_MIN_HEIGHT) NODE_HEIGHT else BOX
            val parts = UiLayout.column(gap = GAP) {
                fixed(TAB_HEIGHT + pageHeight(nodeHeight), UiLayout.column {
                    fixed(TAB_HEIGHT, "tabs")
                    weight("page", min = 1)
                })
                weight(UiLayout.row(gap = GAP) {
                    weight("challenger", 35, min = 1)
                    weight("mechanics", 29, min = 1)
                    weight("rewards", 36, min = 1)
                }, min = 1)
                fixed(FOOTER_HEIGHT, "footer")
            }.solve(bounds)
            val page = parts["page"]
            val route = UiRect(page.x + 3, page.y + PAGE_MARGIN, (page.width - 6).coerceAtLeast(1), (page.height - PAGE_MARGIN * 2).coerceAtLeast(1))
            return LeagueHubLayout(parts["tabs"], page, route, parts["challenger"], parts["mechanics"], parts["rewards"], parts["footer"], nodeHeight)
        }
    }
}

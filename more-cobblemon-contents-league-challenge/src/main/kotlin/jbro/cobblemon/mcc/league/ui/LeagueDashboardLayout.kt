package jbro.cobblemon.mcc.league.ui

/** Logical GUI coordinates. Minecraft applies the player's GUI scale afterward. */
data class LeagueDashboardRect(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

data class LeagueDashboardLayout(
    val shell: LeagueDashboardRect,
    val header: LeagueDashboardRect,
    val route: LeagueDashboardRect,
    val detail: LeagueDashboardRect,
    val status: LeagueDashboardRect,
    val footer: LeagueDashboardRect,
    val face: LeagueDashboardRect,
    val ball: LeagueDashboardRect,
    val stats: LeagueDashboardRect,
    val brandScale: Float
) {
    companion object {
        fun calculate(screenWidth: Int, screenHeight: Int): LeagueDashboardLayout {
            require(screenWidth > 0 && screenHeight > 0)
            val shellWidth = (screenWidth - 12).coerceIn(1, 720)
            val shellHeight = (screenHeight - 8).coerceIn(1, 400)
            val shell = LeagueDashboardRect((screenWidth - shellWidth) / 2,
                (screenHeight - shellHeight) / 2, shellWidth, shellHeight)
            val inset = 6
            val innerLeft = shell.left + inset
            val innerWidth = shell.width - inset * 2
            val headerHeight = if (shellHeight >= 220) 40 else 34
            val routeHeight = if (shellHeight >= 220) 44 else 38
            val footerHeight = 27
            val header = LeagueDashboardRect(shell.left + 4, shell.top + 4, shell.width - 8, headerHeight - 4)
            val route = LeagueDashboardRect(innerLeft, shell.top + headerHeight, innerWidth, routeHeight)
            val footer = LeagueDashboardRect(innerLeft, shell.bottom - footerHeight, innerWidth, footerHeight - 3)
            val bodyTop = route.bottom + 5
            val bodyHeight = (footer.top - bodyTop - 5).coerceAtLeast(1)
            val gap = 5
            val detailWidth = (innerWidth * 55 / 100).coerceAtLeast(1)
            val detail = LeagueDashboardRect(innerLeft, bodyTop, detailWidth, bodyHeight)
            val status = LeagueDashboardRect(detail.right + gap, bodyTop,
                (innerWidth - detailWidth - gap).coerceAtLeast(1), bodyHeight)
            val statsWidth = if (shellWidth >= 380) 75 else 60
            val stats = LeagueDashboardRect(header.right - statsWidth, header.top + 2, statsWidth, 32)
            val face = LeagueDashboardRect(stats.left - 125, header.top + 5, 24, 24)
            val ball = LeagueDashboardRect(face.right + 5, header.top + 9, 16, 16)
            return LeagueDashboardLayout(shell, header, route, detail, status, footer, face, ball,
                stats, if (shellWidth >= 380) 2f else 1.25f)
        }
    }
}

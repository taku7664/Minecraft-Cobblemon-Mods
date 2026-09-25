package jbro.cobblemon.morebattlecontent.leaguechallenge.client

import kotlin.math.min

internal data class LeagueUiRect(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height

    fun contains(other: LeagueUiRect): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

    fun overlaps(other: LeagueUiRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}

internal data class LeagueHomeLayout(
    val shell: LeagueUiRect,
    val header: LeagueUiRect,
    val badges: LeagueUiRect,
    val challenge: LeagueUiRect,
    val footer: LeagueUiRect,
    val actionButton: LeagueUiRect,
    val stacked: Boolean
) {
    companion object {
        private const val MAX_WIDTH = 560
        private const val MAX_HEIGHT = 320
        private const val GAP = 6
        private const val HEADER_HEIGHT = 32
        private const val FOOTER_HEIGHT = 34

        fun calculate(viewportWidth: Int, viewportHeight: Int): LeagueHomeLayout {
            require(viewportWidth >= 320 && viewportHeight >= 240) {
                "League home spike requires at least a 320x240 viewport"
            }
            val margin = if (viewportWidth <= 340) 8 else 12
            val shellWidth = min(viewportWidth - margin * 2, MAX_WIDTH)
            val shellHeight = min(viewportHeight - margin * 2, MAX_HEIGHT)
            val shell = LeagueUiRect(
                left = (viewportWidth - shellWidth) / 2,
                top = (viewportHeight - shellHeight) / 2,
                width = shellWidth,
                height = shellHeight
            )
            val header = LeagueUiRect(shell.left, shell.top, shell.width, HEADER_HEIGHT)
            val footer = LeagueUiRect(shell.left, shell.bottom - FOOTER_HEIGHT, shell.width, FOOTER_HEIGHT)
            val contentLeft = shell.left + GAP
            val contentTop = header.bottom + GAP
            val contentWidth = shell.width - GAP * 2
            val contentHeight = footer.top - GAP - contentTop
            val stacked = viewportWidth < 400

            val badges: LeagueUiRect
            val challenge: LeagueUiRect
            if (stacked) {
                val badgeHeight = (contentHeight - GAP) / 2
                badges = LeagueUiRect(contentLeft, contentTop, contentWidth, badgeHeight)
                challenge = LeagueUiRect(contentLeft, badges.bottom + GAP, contentWidth, contentHeight - badgeHeight - GAP)
            } else {
                val badgeWidth = ((contentWidth - GAP) * 56) / 100
                badges = LeagueUiRect(contentLeft, contentTop, badgeWidth, contentHeight)
                challenge = LeagueUiRect(badges.right + GAP, contentTop, contentWidth - badgeWidth - GAP, contentHeight)
            }

            val buttonWidth = min(148, shell.width / 2)
            val actionButton = LeagueUiRect(
                left = footer.right - GAP - buttonWidth,
                top = footer.top + 7,
                width = buttonWidth,
                height = 20
            )
            return LeagueHomeLayout(shell, header, badges, challenge, footer, actionButton, stacked)
        }
    }
}

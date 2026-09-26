package jbro.cobblemon.morebattlecontent.leaguechallenge.client

internal data class LeagueChallengeCopyPlacement(
    val titleTop: Int,
    val detailTop: Int
) {
    companion object {
        fun calculate(titleTop: Int, titleLineCount: Int): LeagueChallengeCopyPlacement {
            val measuredTitleLines = titleLineCount.coerceAtLeast(1)
            return LeagueChallengeCopyPlacement(
                titleTop = titleTop,
                detailTop = titleTop + measuredTitleLines * LINE_HEIGHT + COPY_GAP
            )
        }
    }
}

private const val LINE_HEIGHT = 10
private const val COPY_GAP = 3

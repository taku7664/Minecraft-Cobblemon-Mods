package jbro.cobblemon.morebattlecontent.leaguechallenge.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LeagueChallengeCopyPlacementTest {
    @Test
    fun `detail starts after every wrapped title line`() {
        val placement = LeagueChallengeCopyPlacement.calculate(
            titleTop = 24,
            titleLineCount = 3
        )

        assertEquals(24, placement.titleTop)
        assertEquals(57, placement.detailTop)
    }

    @Test
    fun `empty measured title still reserves one line`() {
        val placement = LeagueChallengeCopyPlacement.calculate(
            titleTop = 24,
            titleLineCount = 0
        )

        assertEquals(37, placement.detailTop)
    }
}

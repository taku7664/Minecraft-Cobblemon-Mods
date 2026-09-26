package jbro.cobblemon.morebattlecontent.leaguechallenge.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LeagueRankTest {
    @Test
    fun `rank follows every adopted badge boundary`() {
        assertEquals(LeagueRank.POKE_BALL, LeagueRank.fromProgress(0, championDefeated = false))
        assertEquals(LeagueRank.POKE_BALL, LeagueRank.fromProgress(2, championDefeated = false))
        assertEquals(LeagueRank.GREAT_BALL, LeagueRank.fromProgress(3, championDefeated = false))
        assertEquals(LeagueRank.GREAT_BALL, LeagueRank.fromProgress(4, championDefeated = false))
        assertEquals(LeagueRank.ULTRA_BALL, LeagueRank.fromProgress(5, championDefeated = false))
        assertEquals(LeagueRank.ULTRA_BALL, LeagueRank.fromProgress(7, championDefeated = false))
        assertEquals(LeagueRank.MASTER_BALL, LeagueRank.fromProgress(8, championDefeated = false))
        assertEquals(LeagueRank.CHAMPION, LeagueRank.fromProgress(8, championDefeated = true))
    }

    @Test
    fun `invalid badge counts are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            LeagueRank.fromProgress(-1, championDefeated = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LeagueRank.fromProgress(9, championDefeated = false)
        }
    }
}

package jbro.cobblemon.policy.chat

import jbro.cobblemon.mcc.league.ui.LeagueRank
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RankIconTest {
    @Test
    fun `each ball rank has its own glyph`() {
        val balls = listOf(LeagueRank.POKE_BALL, LeagueRank.GREAT_BALL, LeagueRank.ULTRA_BALL, LeagueRank.MASTER_BALL)
        assertEquals(balls.size, balls.map { RankIcon.of(it).glyph }.toSet().size)
    }

    @Test
    fun `champion shows the master ball`() {
        assertEquals(RankIcon.MASTER_BALL, RankIcon.of(LeagueRank.CHAMPION))
    }

    @Test
    fun `every glyph is in the font file`() {
        val font = checkNotNull(javaClass.getResource("/assets/jbro_policy/font/rank_icons.json")).readText()
        RankIcon.entries.forEach { icon ->
            val escaped = "\\u%04x".format(icon.glyph.single().code)
            assertTrue(escaped in font) { "$icon glyph $escaped is missing from rank_icons.json" }
        }
    }
}

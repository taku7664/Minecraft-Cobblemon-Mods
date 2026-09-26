package jbro.cobblemon.morebattlecontent.leaguechallenge.ui

import jbro.cobblemon.morebattlecontent.leaguechallenge.network.LeagueChallengeView
import jbro.cobblemon.morebattlecontent.leaguechallenge.network.LeagueView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class LeagueHomePresentationTest {
    private fun view(run: String? = null): LeagueView {
        val challenges = (1..8).map { index ->
            LeagueChallengeView("gym_$index", "gym.$index", if (index < 3) "CLEARED" else "AVAILABLE", 10 + index * 5)
        } + LeagueChallengeView("final", "final.name", "LOCKED", 100)
        return LeagueView(UUID.randomUUID(), 1, 1, "league.name", 2, "POKE_BALL", 20, false,
            0, challenges, run, false, false)
    }

    @Test fun `eight gyms and final route remain distinct`() {
        val presentation = LeagueHomePresentation.from(view(), "gym_3")
        assertEquals(8, presentation.gyms.size)
        assertEquals(listOf("final"), presentation.finals.map { it.id })
        assertEquals("gym_3", presentation.focused?.id)
    }

    @Test fun `active run takes visual priority over stale local selection`() {
        val presentation = LeagueHomePresentation.from(view(run = "final"), "gym_3")
        assertEquals("final", presentation.focused?.id)
    }

    @Test fun `empty catalog has no fake selected trainer`() {
        assertNull(LeagueHomePresentation.from(view().copy(challenges = emptyList()), null).focused)
    }
}

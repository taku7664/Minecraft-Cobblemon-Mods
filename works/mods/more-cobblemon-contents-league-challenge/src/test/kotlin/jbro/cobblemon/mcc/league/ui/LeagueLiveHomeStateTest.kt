package jbro.cobblemon.mcc.league.ui

import java.util.UUID
import jbro.cobblemon.mcc.league.network.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LeagueLiveHomeStateTest {
    private fun view() = LeagueView(UUID.randomUUID(), 1, 1, "league.name", 1, "POKE_BALL", 20, false, 10,
        listOf(LeagueChallengeView("test:g1", "first", "CLEARED", 20),
            LeagueChallengeView("test:g2", "second", "AVAILABLE", 30),
            LeagueChallengeView("test:g3", "third", "LOCKED", 40)), null, false, false)

    /** A route whose gyms are beaten and whose finals are open but not started. */
    private fun finalsNext() = view().copy(challenges = listOf(LeagueChallengeView("test:g1", "first", "CLEARED", 20, stage = 1),
        LeagueChallengeView("test:f1", "league", "AVAILABLE", 80, stage = 2, finalStage = true)),
        finalists = listOf(LeagueChallengeView("test:f1", "aaron", "AVAILABLE", 80, stage = 1, finalStage = true),
            LeagueChallengeView("test:f2", "cynthia", "LOCKED", 80, stage = 2, finalStage = true)))

    @Test fun `open finals not yet started stay one route entry that starts the run`() {
        val state = LeagueLiveHomeState()
        state.accept(finalsNext())
        assertFalse(state.finalsView)
        assertEquals(listOf("test:g1", "test:f1"), state.challenges.map { it.id })
        assertEquals("test:f1", state.selectedId)
        assertTrue(state.canStart)
    }

    @Test fun `a run on the finals keeps their trainers shown and a won route goes back to the gyms`() {
        val state = LeagueLiveHomeState()
        val running = finalsNext().copy(runChallenge = "test:f2", awaitingNext = false,
            runEntry = LeagueChallengeView("test:f1", "league", "AVAILABLE", 80, stage = 2, finalStage = true))
        state.accept(running)
        assertTrue(state.finalsView)
        val won = finalsNext().copy(champion = true, challenges = finalsNext().challenges.map { it.copy(status = "CLEARED") })
        state.accept(won)
        assertFalse(state.finalsView)
        assertEquals(listOf("test:g1", "test:f1"), state.challenges.map { it.id })
    }

    @Test fun `default selects next gym and sends only intent once`() {
        val state = LeagueLiveHomeState()
        state.accept(view())
        assertEquals("test:g2", state.selectedId)
        assertTrue(state.begin(LeagueAction.START))
        assertFalse(state.begin(LeagueAction.START))
        assertTrue(state.pending)
        assertEquals(1, state.view!!.badges)
    }

    @Test fun `locks and pending rewards never become local victories`() {
        val state = LeagueLiveHomeState()
        val view = view()
        state.accept(view)
        state.select("test:g3")
        assertFalse(state.canStart)
        state.accept(view.copy(pendingRewards = true))
        state.select("test:g2")
        assertFalse(state.canStart)
        assertFalse(state.begin(LeagueAction.NEXT))
    }

    @Test fun `server rejection unlocks retry and selection survives same session updates`() {
        val state = LeagueLiveHomeState()
        val view = view()
        state.accept(view)
        state.select("test:g1")
        state.begin(LeagueAction.START)
        state.accept(view.copy(errorKey = "server.error"))
        assertFalse(state.pending)
        assertEquals("test:g1", state.selectedId)
        assertEquals("server.error", state.view!!.errorKey)
        state.accept(view.copy(nonce = UUID.randomUUID()))
        assertEquals("test:g2", state.selectedId)
    }

    @Test fun `run controls depend on server phase and disconnect clears everything`() {
        val state = LeagueLiveHomeState()
        val view = view()
        state.accept(view.copy(runChallenge = "test:f1", awaitingNext = false))
        assertFalse(state.canStart)
        assertFalse(state.canNext)
        assertTrue(state.canCancel)
        state.accept(view.copy(runChallenge = "test:f1", awaitingNext = true))
        assertTrue(state.canNext)
        state.accept(null)
        assertNull(state.view)
        assertFalse(state.canCancel)
    }
}

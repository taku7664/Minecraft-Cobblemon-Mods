package jbro.cobblemon.morebattlecontent.leaguechallenge.ui

import java.util.UUID
import jbro.cobblemon.morebattlecontent.leaguechallenge.network.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LeagueLiveHomeStateTest {
    private fun view() = LeagueView(UUID.randomUUID(), 1, 1, "league.name", 1, "POKE_BALL", 20, false, 10,
        listOf(LeagueChallengeView("test:g1", "first", "CLEARED", 20),
            LeagueChallengeView("test:g2", "second", "AVAILABLE", 30),
            LeagueChallengeView("test:g3", "third", "LOCKED", 40)), null, false, false)

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

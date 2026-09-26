package jbro.cobblemon.morebattlecontent.leaguechallenge.system

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class LeagueEngineTest {
    private fun catalog(): LeagueCatalog {
        val gyms = (1..8).map { "test:g$it" }
        val finals = (1..5).map { "test:f$it" }
        return LeagueCatalog("test:league", "league.name", 10, gyms, finals,
            (gyms + finals).associateWith { id -> Challenge(id, "trainer.name", listOf("pikachu level=10"),
                if (id in gyms) "pokebadges:${id.substringAfter(':')}" else null,
                if (id in gyms) 10 + (gyms.indexOf(id) + 1) * 10 else 100,
                10, 0, "NONE", "SINGLE", null, false) })
    }
    private fun party() = (1..6).map { "locked-pokemon-$it" }
    private fun delivered(state: LeagueProgress) = state.copy(rewards = state.rewards.map { it.copy(badgeDone = true, bpDone = true) })

    @Test fun `cannot skip gym or enter league early`() {
        val engine = LeagueEngine(catalog())
        assertThrows(IllegalArgumentException::class.java) { engine.begin(LeagueProgress(), "test:g2", party()) }
        assertThrows(IllegalArgumentException::class.java) { engine.begin(LeagueProgress(), "test:f1", party()) }
    }

    @Test fun `eight gyms then five wins unlock champion and duplicate result is harmless`() {
        val engine = LeagueEngine(catalog())
        var state = LeagueProgress()
        for (index in 1..8) {
            state = engine.begin(state, "test:g$index", party())
            val token = state.run!!.battleToken
            state = engine.finish(state, token, true, 1000)
            assertEquals(state, engine.finish(state, token, true, 1001))
            state = delivered(state)
        }
        assertFalse(state.champion)
        assertEquals(8, engine.badgeCount(state))
        state = engine.begin(state, "test:f1", party())
        for (index in 1..5) {
            assertEquals("test:f$index", state.run!!.challengeId)
            state = engine.finish(state, state.run!!.battleToken, true, 2000 + index.toLong())
            if (index < 5) state = engine.next(delivered(state))
        }
        assertTrue(state.champion)
        assertEquals(2005L, state.championAt)
        assertEquals(100, engine.cap(state))
        assertNull(state.run)
        assertEquals(13, state.cleared.size)
    }

    @Test fun `loss cancels run without erasing badges and stale callback cannot win next battle`() {
        val engine = LeagueEngine(catalog())
        var state = LeagueProgress(cleared = catalog().gyms.toSet())
        state = engine.begin(state, "test:f1", party())
        val old = state.run!!.battleToken
        state = engine.finish(state, old, false, 1)
        assertNull(state.run)
        assertEquals(8, engine.badgeCount(state))
        state = engine.begin(state, "test:f1", party())
        assertEquals(state, engine.finish(state, old, true, 2))
    }

    @Test fun `pinned encounter survives reload and party list is copied`() {
        val engine = LeagueEngine(catalog())
        val party = party().toMutableList()
        val state = engine.begin(LeagueProgress(), "test:g1", party)
        party.clear()
        assertEquals(6, state.run!!.party.size)
        val replacement = catalog().copy(challenges = catalog().challenges.mapValues { it.value.copy(firstBp = 999) })
        val completed = LeagueEngine(replacement).finish(state, state.run.battleToken, true, 1)
        assertEquals(10L, completed.rewards.single().bp)
    }

    @Test fun `round trip keeps transaction identities and unknown schema fails closed`() {
        val engine = LeagueEngine(catalog())
        var state = engine.begin(LeagueProgress(), "test:g1", party())
        state = engine.finish(state, state.run!!.battleToken, true, 1)
        assertEquals(state, LeagueProgressCodec.decode(LeagueProgressCodec.encode(state)))
        assertThrows(IllegalArgumentException::class.java) { LeagueProgressCodec.decode("{\"schema_version\":999}") }
    }

    @Test fun `pending rewards prevent advancing and rematches cannot farm first rewards`() {
        val engine = LeagueEngine(catalog())
        var state = engine.begin(LeagueProgress(), "test:g1", party())
        state = engine.finish(state, state.run!!.battleToken, true, 1)
        assertThrows(IllegalArgumentException::class.java) { engine.begin(state, "test:g2", party()) }
        state = engine.begin(delivered(state), "test:g1", party())
        state = engine.finish(state, state.run!!.battleToken, true, 2)
        assertEquals(1, state.rewards.size)
        assertEquals(10L, state.rewards.single().bp)
        assertEquals(1, engine.badgeCount(state))
    }

    @Test fun `next cannot run twice and cancel preserves paid receipts`() {
        val engine = LeagueEngine(catalog())
        var state = engine.begin(LeagueProgress(cleared = catalog().gyms.toSet()), "test:f1", party())
        state = engine.finish(state, state.run!!.battleToken, true, 1)
        state = engine.next(delivered(state))
        assertThrows(IllegalArgumentException::class.java) { engine.next(state) }
        val cancelled = engine.cancel(state)
        assertEquals(state.rewards, cancelled.rewards)
        assertNull(cancelled.run)
        assertFalse(cancelled.champion)
    }

    @Test fun `receipt capacity is reserved before all five fights rather than losing a later win`() {
        val engine = LeagueEngine(catalog())
        val state = LeagueProgress(cleared = catalog().gyms.toSet(), rewards = (1..4093).map {
            LeagueReward(UUID.randomUUID(), "test:old", null, 1, bpDone = true)
        })
        val failure = assertThrows(IllegalArgumentException::class.java) { engine.begin(state, "test:f1", party()) }
        assertEquals("history_full", failure.message)
    }

    @Test fun `missing and malformed progress never becomes a clean account`() {
        for (raw in listOf("{}", "{\"schema_version\":1,\"progress\":{}}", "{\"schema_version\":1,\"progress\":null}")) {
            assertThrows(RuntimeException::class.java) { LeagueProgressCodec.decode(raw) }
        }
    }
}

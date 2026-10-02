package jbro.cobblemon.mcc.league.system

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
    private fun hardCatalog(): LeagueCatalog {
        val base = catalog()
        val hardGyms = (1..8).map { "test:hg$it" }
        val hardFinals = (1..5).map { "test:hf$it" }
        val hard = (hardGyms + hardFinals).associateWith { id -> Challenge(id, "trainer.name", listOf("pikachu level=10"), null,
            if (id == hardGyms.last() || id in hardFinals) 100 else 100, 30, 0, "MEGA", "SINGLE", null, false, 5) }
        return base.copy(challenges = base.challenges + hard, hardGyms = hardGyms, hardFinals = hardFinals)
    }
    private fun win(engine: LeagueEngine, state: LeagueProgress, id: String): LeagueProgress {
        var next = engine.begin(state, id, party())
        next = engine.finish(next, next.run!!.battleToken, true, 1000)
        return delivered(next)
    }
    private fun party() = (1..6).map { "locked-pokemon-$it" }

    @Test fun `a challenge on its own is a gym, an Elite Four member or the Champion of its route`() {
        val engine = LeagueEngine(hardCatalog())
        assertEquals("gym", engine.stage("test:g3"))
        assertEquals("elite_four", engine.stage("test:f2"))
        assertEquals("champion", engine.stage("test:f5"))
        assertEquals("hard_gym", engine.stage("test:hg8"))
        assertEquals("hard_elite_four", engine.stage("test:hf4"))
        assertEquals("hard_champion", engine.stage("test:hf5"))
    }

    @Test fun `an operator sets progress up to just before a challenge, to everything, or back to nothing`() {
        val engine = LeagueEngine(hardCatalog())
        val beforeHardChampion = engine.clearedBefore(LeagueProgress(), "test:hf5", 3000)
        assertEquals(engine.route().dropLast(1).toSet(), beforeHardChampion.cleared)
        assertTrue(beforeHardChampion.champion && !beforeHardChampion.hardChampion)
        assertEquals(100, engine.cap(beforeHardChampion))
        assertEquals(8, engine.badgeCount(beforeHardChampion))
        // The hard finals are still one run from their first battle.
        assertEquals("test:hf1", engine.begin(beforeHardChampion, "test:hf1", party()).run!!.challengeId)

        val all = engine.clearedBefore(beforeHardChampion, null, 4000)
        assertTrue(all.champion && all.hardChampion)
        assertEquals(3000, all.championAt)

        val reset = engine.clearedBefore(all, engine.route().first(), 5000)
        assertTrue(reset.cleared.isEmpty() && !reset.champion && !reset.hardChampion && reset.championAt == null)
        assertEquals(10, engine.cap(reset))
        assertEquals("unknown_challenge", assertThrows(IllegalArgumentException::class.java) {
            engine.clearedBefore(LeagueProgress(), "test:nobody", 0)
        }.message)
        val running = engine.begin(LeagueProgress(), "test:g1", party())
        assertEquals("run_active", assertThrows(IllegalArgumentException::class.java) { engine.clearedBefore(running, null, 0) }.message)
    }

    @Test fun `the hard route opens only for the normal Champion and has its own title`() {
        val engine = LeagueEngine(hardCatalog())
        var state = LeagueProgress()
        assertEquals("hard_locked", assertThrows(IllegalArgumentException::class.java) { engine.begin(state, "test:hg1", party()) }.message)
        for (index in 1..8) state = win(engine, state, "test:g$index")
        state = engine.begin(state, "test:f1", party())
        for (index in 1..5) {
            state = delivered(engine.finish(state, state.run!!.battleToken, true, 2000))
            if (index < 5) state = engine.next(state)
        }
        assertTrue(state.champion)
        assertFalse(state.hardChampion)
        assertTrue(engine.hardUnlocked(state))
        assertThrows(IllegalArgumentException::class.java) { engine.begin(state, "test:hg2", party()) }
        assertThrows(IllegalArgumentException::class.java) { engine.begin(state, "test:hf1", party()) }
        for (index in 1..8) state = win(engine, state, "test:hg$index")
        assertEquals(8, engine.badgeCount(state))
        assertEquals(100, engine.cap(state))
        state = engine.begin(state, "test:hf1", party())
        assertEquals((1..5).map { "test:hf$it" }, state.run!!.encounters.map { it.id })
        for (index in 1..5) {
            state = delivered(engine.finish(state, state.run!!.battleToken, true, 3000))
            if (index < 5) state = engine.next(state)
        }
        assertTrue(state.hardChampion)
        assertEquals(state, LeagueProgressCodec.decode(LeagueProgressCodec.encode(state)))
    }

    @Test fun `each battle tells clients whether it is a gym, the Elite Four or the Champion, and which route`() {
        val engine = LeagueEngine(hardCatalog())
        var state = LeagueProgress()
        assertEquals("gym", engine.stage(engine.begin(state, "test:g1", party()).run!!))
        for (index in 1..8) state = win(engine, state, "test:g$index")
        var run = engine.begin(state, "test:f1", party())
        val stages = mutableListOf(engine.stage(run.run!!))
        for (index in 1..4) {
            run = engine.next(delivered(engine.finish(run, run.run!!.battleToken, true, 2000)))
            stages += engine.stage(run.run!!)
        }
        assertEquals(List(4) { "elite_four" } + "champion", stages)
        state = delivered(engine.finish(run, run.run!!.battleToken, true, 2000))
        assertEquals("hard_gym", engine.stage(engine.begin(state, "test:hg1", party()).run!!))
        for (index in 1..8) state = win(engine, state, "test:hg$index")
        run = engine.begin(state, "test:hf1", party())
        assertEquals("hard_elite_four", engine.stage(run.run!!))
        assertEquals("hard_champion", engine.stage(run.run!!.copy(index = 4)))
    }

    @Test fun `a league without a hard route never opens one`() {
        val engine = LeagueEngine(catalog())
        assertFalse(engine.hardUnlocked(LeagueProgress(champion = true, championAt = 1)))
    }
    private fun delivered(state: LeagueProgress) = state.copy(rewards = state.rewards.map { it.copy(badgeDone = true, bpDone = true) })

    @Test fun `the cap follows the current catalog for progress saved under older caps`() {
        val engine = LeagueEngine(catalog())
        // Saved when the first gym still unlocked 15; this catalog gives 20 for it.
        assertEquals(20, engine.cap(LeagueProgress(cleared = setOf("test:g1"), unlockedCap = 15)))
        // A stored cap above anything cleared, or a challenge the catalog no longer has, earns nothing.
        assertEquals(10, engine.cap(LeagueProgress(unlockedCap = 70)))
        assertEquals(30, engine.cap(LeagueProgress(cleared = setOf("test:g1", "test:g2", "test:removed"))))
    }

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

    @Test fun `a party of one to six may challenge and survives a reload`() {
        val engine = LeagueEngine(catalog())
        assertThrows(IllegalArgumentException::class.java) { engine.begin(LeagueProgress(), "test:g1", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { engine.begin(LeagueProgress(), "test:g1", party() + "extra") }
        val solo = engine.begin(LeagueProgress(), "test:g1", party().take(1))
        assertEquals(1, solo.run!!.party.size)
        assertEquals(solo, LeagueProgressCodec.decode(LeagueProgressCodec.encode(solo)))
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

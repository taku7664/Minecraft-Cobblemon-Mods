package jbro.cobblemon.mcc.league.system

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LeagueExpansionTest {
    private fun catalog(): LeagueCatalog {
        fun challenge(id: String, cap: Int = 80, badge: String? = null) = Challenge(id, id,
            listOf("magikarp level=20 moves=splash"), badge, cap, 10, 0, "DYNAMAX", "SINGLE", null, false,
            battleCap = 30)
        val stages = (1..8).map { listOf("test:g$it") }.toMutableList()
        stages[3] = listOf("test:g4", "test:g4b")
        val gyms = stages.flatten()
        val finals = (1..7).map { "test:f$it" }
        val all = gyms.associateWith { challenge(it, badge = "$it-badge") } + finals.associateWith { challenge(it) } +
            mapOf("test:f5b" to challenge("test:f5b"))
        return LeagueCatalog("test:galar", "galar", 80, gyms, finals, all,
            gymStages = stages, finalChoices = mapOf("test:f5" to listOf("test:f5", "test:f5b")),
            playerMechanic = "DYNAMAX", lockMechanicsUntilChampion = false)
    }
    private fun delivered(p: LeagueProgress) = p.copy(rewards = p.rewards.map { it.copy(badgeDone = true, bpDone = true) })

    @Test fun `either branch unlocks the following stage and the other remains available`() {
        val e = LeagueEngine(catalog())
        val p = LeagueProgress(cleared = setOf("test:g1", "test:g2", "test:g3", "test:g4b"))
        assertEquals(4, e.badgeCount(p))
        assertTrue(e.available(p, "test:g5"))
        assertTrue(e.available(p, "test:g4"))
        assertFalse(e.available(p, "test:g6"))
        assertEquals(4, e.badgeCount(p.copy(cleared = p.cleared + "test:g4")))
    }

    @Test fun `seven finals pin the selected branch and award a title only at the end`() {
        val c = catalog(); val e = LeagueEngine(c)
        var p = e.begin(LeagueProgress(cleared = c.gymStages.map { it.first() }.toSet()), "test:f1", listOf("{}"), "test:f5b")
        assertEquals("test:f5b", p.run!!.encounters[4].id)
        assertEquals(30, p.run!!.battleCap)
        assertEquals("DYNAMAX", p.run!!.mechanic)
        for (i in 0..6) {
            val token = p.run!!.battleToken
            p = delivered(e.finish(p, token, true, 10))
            assertEquals(i == 6, p.champion)
            assertEquals(p, e.finish(p, token, true, 11))
            if (i < 6) p = e.next(p)
        }
        assertNull(p.run)
        assertEquals(p, LeagueProgressCodec.decode(LeagueProgressCodec.encode(p)))
    }

    @Test fun `a gym replay keeps its own cap rather than the players earned cap`() {
        val c = catalog(); val e = LeagueEngine(c)
        val p = e.begin(LeagueProgress(cleared = c.gyms.toSet(), unlockedCap = 100), "test:g1", listOf("{}"))
        assertEquals(30, p.run!!.battleCap)
    }

    @Test fun `a foreign finals branch is rejected`() {
        val c = catalog(); val e = LeagueEngine(c)
        assertThrows(IllegalArgumentException::class.java) {
            e.begin(LeagueProgress(cleared = c.gyms.toSet()), "test:f1", listOf("{}"), "test:g4b")
        }
    }
}

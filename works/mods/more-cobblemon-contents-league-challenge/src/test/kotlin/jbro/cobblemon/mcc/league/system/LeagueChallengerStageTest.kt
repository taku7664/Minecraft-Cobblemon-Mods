package jbro.cobblemon.mcc.league.system

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Challengers: trainers fought one at a time after the gyms, each a stage of its own, before the finals open. */
class LeagueChallengerStageTest {
    private fun catalog(): LeagueCatalog {
        fun challenge(id: String, badge: String? = null) = Challenge(id, id,
            listOf("magikarp level=20 moves=splash"), badge, 80, 10, 0, "DYNAMAX", "SINGLE", null, false, battleCap = 30)
        val gyms = (1..8).map { "test:g$it" }
        val challengers = listOf("test:c1", "test:c2")
        val finals = listOf("test:f1", "test:f2")
        val hardGyms = (1..8).map { "test:hg$it" }
        val all = gyms.associateWith { challenge(it, badge = "$it-badge") } +
            (challengers + finals + hardGyms + listOf("test:hc1", "test:hf1")).associateWith { challenge(it) }
        return LeagueCatalog("test:galar", "galar", 80, gyms, finals, all, hardGyms = hardGyms, hardFinals = listOf("test:hf1"),
            playerMechanic = "DYNAMAX", lockMechanicsUntilChampion = false,
            challengers = challengers, hardChallengers = listOf("test:hc1"))
    }
    private fun delivered(p: LeagueProgress) = p.copy(rewards = p.rewards.map { it.copy(badgeDone = true, bpDone = true) })

    @Test fun `challengers open one after another after the gyms and the finals only after them`() {
        val c = catalog(); val e = LeagueEngine(c)
        val gyms = LeagueProgress(cleared = c.gyms.toSet())
        assertFalse(e.available(LeagueProgress(cleared = c.gyms.dropLast(1).toSet()), "test:c1"))
        assertTrue(e.available(gyms, "test:c1"))
        assertFalse(e.available(gyms, "test:c2"))
        assertFalse(e.available(gyms, "test:f1"))
        assertTrue(e.available(gyms.copy(cleared = gyms.cleared + "test:c1"), "test:c2"))
        val both = gyms.copy(cleared = gyms.cleared + "test:c1" + "test:c2")
        assertTrue(e.available(both, "test:f1"))
        assertThrows(IllegalArgumentException::class.java) { e.begin(gyms, "test:f1", listOf("{}")) }
        assertEquals(8, e.badgeCount(both))
    }

    @Test fun `a challenger is one battle that gives no title and the finals stay one run`() {
        val c = catalog(); val e = LeagueEngine(c)
        var p = e.begin(LeagueProgress(cleared = c.gyms.toSet()), "test:c1", listOf("{}"))
        assertEquals(listOf("test:c1"), p.run!!.encounters.map { it.id })
        assertEquals("tournament", e.stage(p.run!!))
        p = delivered(e.finish(p, p.run!!.battleToken, true, 10))
        assertNull(p.run)
        assertFalse(p.champion)
        p = e.begin(p, "test:c2", listOf("{}"))
        p = delivered(e.finish(p, p.run!!.battleToken, true, 11))
        assertTrue("test:c2" in p.cleared)
        p = e.begin(p, "test:f1", listOf("{}"))
        assertEquals(listOf("test:f1", "test:f2"), p.run!!.encounters.map { it.id })
        p = delivered(e.finish(p, p.run!!.battleToken, true, 12))
        assertFalse(p.champion)
        p = e.next(p)
        assertEquals("champion", e.stage(p.run!!))
        p = delivered(e.finish(p, p.run!!.battleToken, true, 13))
        assertTrue(p.champion)
        assertNull(p.run)
    }

    @Test fun `the route lists challengers between the gyms and the finals on both routes`() {
        val c = catalog(); val e = LeagueEngine(c)
        assertEquals(c.gyms + listOf("test:c1", "test:c2", "test:f1", "test:f2") + c.hardGyms + listOf("test:hc1", "test:hf1"), e.route())
        assertEquals("tournament", e.stage("test:c2"))
        assertEquals("hard_tournament", e.stage("test:hc1"))
        val champion = e.clearedBefore(LeagueProgress(), "test:hg1", 5)
        assertTrue(champion.champion)
        assertTrue(e.available(champion, "test:hg1"))
        assertFalse(e.available(champion.copy(cleared = champion.cleared + c.hardGyms), "test:hf1"))
        assertTrue(e.available(champion.copy(cleared = champion.cleared + c.hardGyms + "test:hc1"), "test:hf1"))
    }

    @Test fun `a challenger cannot carry a badge`() {
        val c = catalog()
        assertThrows(IllegalArgumentException::class.java) {
            c.copy(challenges = c.challenges + ("test:c1" to c.challenges.getValue("test:c1").copy(badge = "test:badge")))
        }
    }
}

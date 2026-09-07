package jbro.cobblemon.morebattlecontent.betterai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EmbeddedPolicyComparisonTest {
    @Test
    fun `comparison profiles preserve default and support explicit boss without changing personality`() {
        val default = EmbeddedPolicyComparison.profileForSkill(0)
        val boss = EmbeddedPolicyComparison.profileForSkill(5)
        assertEquals(jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier.INTRODUCTORY, default.difficulty.tier)
        assertEquals(jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier.BOSS, boss.difficulty.tier)
        assertEquals(default.personality, boss.personality)
        assertEquals(5, boss.skillLevel)
        assertThrows(IllegalArgumentException::class.java) { EmbeddedPolicyComparison.profileForSkill(6) }
        assertThrows(IllegalArgumentException::class.java) { EmbeddedPolicyComparison.profileForSkill(-1) }
    }

    @Test
    fun `four games balance challenger team and seat and rotate execution order`() {
        val schedule = EmbeddedPolicyComparison.schedule(0)
        assertEquals(4, schedule.size)
        assertEquals(4, schedule.toSet().size)
        assertEquals(2, schedule.count { it.challengerP1 })
        assertEquals(2, schedule.count { it.reverseTeams })
        assertEquals(2, schedule.count { it.challengerP1 != it.reverseTeams })
        assertEquals(schedule.toSet(), EmbeddedPolicyComparison.schedule(1).toSet())
        assertNotEquals(schedule.first(), EmbeddedPolicyComparison.schedule(1).first())
    }

    @Test
    fun `pair score uses four games and incomplete outcomes retain uncertainty`() {
        val complete = EmbeddedPolicyComparison.score(listOf("WIN", "LOSS", "DRAW", "WIN"))
        assertEquals(0.625, complete.first)
        assertEquals(complete.first, complete.second)
        val incomplete = EmbeddedPolicyComparison.score(listOf("WIN", "LOSS", "DRAW", "INCOMPLETE"))
        assertEquals(0.375, incomplete.first)
        assertEquals(0.625, incomplete.second)
        assertThrows(IllegalArgumentException::class.java) { EmbeddedPolicyComparison.score(listOf("WIN")) }
        assertThrows(IllegalArgumentException::class.java) { EmbeddedPolicyComparison.score(List(4) { "UNKNOWN" }) }
    }
}

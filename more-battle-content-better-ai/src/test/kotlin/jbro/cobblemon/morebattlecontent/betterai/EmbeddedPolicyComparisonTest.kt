package jbro.cobblemon.morebattlecontent.betterai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EmbeddedPolicyComparisonTest {
    @Test
    fun `hypothesis comparison arm changes only the experimental switch and identity`() {
        val current = EmbeddedPolicyComparison.tuning("CURRENT")
        assertEquals(current.copy(id = "current_public_move_hypotheses", lookaheadMoveHypotheses = true),
            EmbeddedPolicyComparison.tuning("CURRENT_PUBLIC_MOVE_HYPOTHESES"))
        assertFalse(current.lookaheadMoveHypotheses)
        assertEquals(Int.MAX_VALUE, current.hypotheticalMoveLimitPerSlot)
        assertFalse(current.reserveHypotheticalPriority)
        assertEquals(current.copy(id = "current_public_move_hypotheses_cap3_priority", lookaheadMoveHypotheses = true,
            hypotheticalMoveLimitPerSlot = 3, reserveHypotheticalPriority = true),
            EmbeddedPolicyComparison.tuning("CURRENT_PUBLIC_MOVE_HYPOTHESES_CAP3_PRIORITY"))
        assertEquals(current.copy(id = "current_public_move_hypotheses_cap3", lookaheadMoveHypotheses = true,
            hypotheticalMoveLimitPerSlot = 3), EmbeddedPolicyComparison.tuning("CURRENT_PUBLIC_MOVE_HYPOTHESES_CAP3"))
        assertThrows(IllegalArgumentException::class.java) { current.copy(hypotheticalMoveLimitPerSlot = 0) }
        assertFalse(EmbeddedPolicyComparison.tuning("LEGACY").lookaheadMoveHypotheses)
        assertThrows(IllegalStateException::class.java) { EmbeddedPolicyComparison.tuning("UNKNOWN") }
    }

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

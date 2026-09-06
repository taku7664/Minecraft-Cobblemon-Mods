package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattleIntegerRange
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicStatRanges
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class Cobblemon173PublicStatHypothesisTest {
    @Test
    fun `public factory preserves bounds across levels and Shedinja`() {
        for (level in 1..100) {
            val public = BattlePublicStatRanges.fromBaseStats(level, 80, 100, 90, 80, 90, 100)
            val adapter = Cobblemon173PublicStatHypothesis.fromBaseStats(level, 80, 100, 90, 80, 90, 100)
            assertEquals(adapter.maxHp, public.maxHp)
            assertEquals(adapter.attack, public.attack)
            assertEquals(adapter.defence, public.defence)
            assertEquals(adapter.specialAttack, public.specialAttack)
            assertEquals(adapter.specialDefence, public.specialDefence)
            assertEquals(adapter.speed, public.speed)
            assertEquals(BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE, public.knowledge)
            assertEquals(BattleIntegerRange(1, 1),
                BattlePublicStatRanges.fromBaseStats(level, 1, 90, 45, 30, 30, 40).maxHp)
        }
        val lowest = BattlePublicStatRanges.fromBaseStats(1, 80, 100, 90, 80, 90, 100)
        assertEquals(BattleIntegerRange(12, 13), lowest.maxHp)
        assertEquals(BattleIntegerRange(6, 7), lowest.attack)
        val highest = BattlePublicStatRanges.fromBaseStats(100, 80, 100, 90, 80, 90, 100)
        assertEquals(BattleIntegerRange(270, 364), highest.maxHp)
        assertEquals(BattleIntegerRange(184, 328), highest.attack)
    }

    @Test
    fun `public factory rejects unsupported levels and nonpositive species stats`() {
        for (level in listOf(0, 101)) {
            assertThrows(IllegalArgumentException::class.java) {
                BattlePublicStatRanges.fromBaseStats(level, 80, 100, 90, 80, 90, 100)
            }
        }
        for (slot in 0..5) for (invalid in listOf(0, -1)) {
            val stats = MutableList(6) { 80 }.also { it[slot] = invalid }
            assertThrows(IllegalArgumentException::class.java) {
                BattlePublicStatRanges.fromBaseStats(50, stats[0], stats[1], stats[2], stats[3], stats[4], stats[5])
            }
        }
    }

    @Test
    fun `opponent ranges use only public base stats and legal build bounds`() {
        val result = Cobblemon173PublicStatHypothesis.fromBaseStats(
            level = 50,
            hp = 80,
            attack = 100,
            defence = 90,
            specialAttack = 80,
            specialDefence = 90,
            speed = 100,
        )

        assertEquals(BattleIntegerRange(140, 187), result.maxHp)
        assertEquals(BattleIntegerRange(94, 167), result.attack)
        assertEquals(BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE, result.knowledge)
    }

    @Test
    fun `own stats stay exact without exposing build inputs`() {
        val result = Cobblemon173PublicStatHypothesis.exactOwn(187, 152, 120, 110, 130, 140)

        assertEquals(BattleIntegerRange(152, 152), result.attack)
        assertEquals(BattleCombatStatKnowledge.EXACT_OWN, result.knowledge)
    }
}

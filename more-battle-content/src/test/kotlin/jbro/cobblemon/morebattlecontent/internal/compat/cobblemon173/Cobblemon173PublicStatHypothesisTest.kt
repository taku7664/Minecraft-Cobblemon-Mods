package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattleIntegerRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class Cobblemon173PublicStatHypothesisTest {
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

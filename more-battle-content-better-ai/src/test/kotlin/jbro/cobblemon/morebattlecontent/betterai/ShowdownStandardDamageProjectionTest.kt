package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleDamageFractionRange
import jbro.cobblemon.morebattlecontent.api.ai.BattleFractionRange
import jbro.cobblemon.morebattlecontent.api.ai.BattleIntegerRange
import jbro.cobblemon.morebattlecontent.api.ai.BattleKnockoutAssessment
import jbro.cobblemon.morebattlecontent.betterai.mechanics.ShowdownStandardDamageProjection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ShowdownStandardDamageProjectionTest {
    @Test
    fun `exact integer hp survives a fraction round trip at the knockout boundary`() {
        val result = hpBoundaryProjection(122.0 / 362)
        assertEquals(BattleFractionRange(5.0 / 16, 5.0 / 16), result.koProbabilityRange)
    }

    @Test
    fun `genuinely higher hp fractions still round upward without a broad epsilon`() {
        for (fraction in listOf(Math.nextUp(122.0 / 362), 122.25 / 362)) {
            assertEquals(BattleFractionRange(3.0 / 16, 3.0 / 16), hpBoundaryProjection(fraction).koProbabilityRange)
        }
    }

    private fun hpBoundaryProjection(fraction: Double) = ShowdownStandardDamageProjection.project(
        level = 100, power = 90, attack = BattleIntegerRange(136, 136), defence = BattleIntegerRange(246, 246),
        targetMaxHp = BattleIntegerRange(362, 362), targetHpFraction = fraction, stab = 1.5, typeMultiplier = 2.0,
    )

    @Test
    fun `gen nine base formula preserves all sixteen Showdown random rolls`() {
        val result = ShowdownStandardDamageProjection.project(
            level = 50,
            power = 100,
            attack = BattleIntegerRange(200, 200),
            defence = BattleIntegerRange(100, 100),
            targetMaxHp = BattleIntegerRange(200, 200),
            targetHpFraction = 1.0,
            stab = 1.5,
            typeMultiplier = 2.0,
        )

        assertEquals(228, result.minimumDamage)
        assertEquals(270, result.maximumDamage)
        assertEquals(BattleDamageFractionRange(1.14, 1.35), result.damageFractionRange)
        assertEquals(BattleFractionRange(1.0, 1.0), result.koProbabilityRange)
        assertEquals(BattleKnockoutAssessment.GUARANTEED, result.knockoutAssessment)
    }

    @Test
    fun `exact stats produce an exact random knockout probability`() {
        val result = ShowdownStandardDamageProjection.project(
            level = 50,
            power = 100,
            attack = BattleIntegerRange(200, 200),
            defence = BattleIntegerRange(100, 100),
            targetMaxHp = BattleIntegerRange(85, 85),
            targetHpFraction = 1.0,
            stab = 1.0,
            typeMultiplier = 1.0,
        )

        assertEquals(
            listOf(76, 77, 78, 79, 80, 81, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90),
            result.minimumHypothesisRolls,
        )
        assertEquals(BattleFractionRange(6.0 / 16.0, 6.0 / 16.0), result.koProbabilityRange)
        assertEquals(BattleKnockoutAssessment.POSSIBLE, result.knockoutAssessment)
    }

    @Test
    fun `public stat hypotheses return bounded rather than fabricated exact probability`() {
        val result = ShowdownStandardDamageProjection.project(
            level = 50,
            power = 100,
            attack = BattleIntegerRange(180, 200),
            defence = BattleIntegerRange(100, 140),
            targetMaxHp = BattleIntegerRange(85, 110),
            targetHpFraction = 1.0,
            stab = 1.0,
            typeMultiplier = 1.0,
        )

        assertEquals(0.0, result.koProbabilityRange.minimum)
        assertEquals(6.0 / 16.0, result.koProbabilityRange.maximum)
        assertEquals(BattleKnockoutAssessment.POSSIBLE, result.knockoutAssessment)
    }

    @Test
    fun `type immunity remains zero rather than being forced to minimum damage`() {
        val result = ShowdownStandardDamageProjection.project(
            level = 50,
            power = 100,
            attack = BattleIntegerRange(200, 200),
            defence = BattleIntegerRange(100, 100),
            targetMaxHp = BattleIntegerRange(100, 100),
            targetHpFraction = 1.0,
            stab = 1.0,
            typeMultiplier = 0.0,
        )

        assertEquals(0, result.minimumDamage)
        assertEquals(0, result.maximumDamage)
        assertEquals(BattleKnockoutAssessment.IMPOSSIBLE, result.knockoutAssessment)
    }
}

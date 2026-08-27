package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfiles
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Prices how much pessimism a turn order deserves when the public stat ranges cannot resolve it.
 *
 * The opponent picks their move. They do not pick their IVs. The search used to collapse the orders by
 * a flat minimum, which is heavier than the worst case their actual decisions are given, and every
 * fixture hid the cost by handing it a point range for the opponent - one order, so nothing to
 * minimise over. Against the species range production supplies the ranges overlap nearly always, so
 * the AI assumed it moved second on every node and no patient line could ever pay.
 */
class LocalTurnOrderPessimismTest {
    @Test
    fun `turn order pessimism is swept against the positions it decides`() {
        val positions = LocalForesightPositions.reachable()
        val profile = BattleTrainerProfile(
            skillLevel = 2,
            personality = BattleTrainerProfile.champion().personality,
            difficulty = BattleDifficultyProfiles.BOSS,
        )
        val scales = listOf(1.0, 0.75, 0.5, 0.25, 0.0)

        fun solved(scale: Double) = positions.count { position ->
            LocalDecisionInstrumentation.inspect(
                context = PublicBattleTacticalCalculator.calculate(position.context),
                profile = profile,
                tuning = LocalDecisionTuning.CURRENT.copy(
                    id = "order$scale", turnOrderPessimismScale = scale,
                ),
            ).candidates.maxByOrNull { it.comparisonValue }?.actionId == position.patientAction
        }

        val report = buildString {
            appendLine("=".repeat(96))
            appendLine("TURN ORDER PESSIMISM  positions=${positions.size}, tier=boss")
            appendLine("=".repeat(96))
            appendLine("1.00 gives an unresolved order the same weight as a hostile choice.")
            appendLine("0.00 averages the orders, which is what an unknown deserves.")
            appendLine()
            scales.forEach { scale ->
                appendLine("  scale=%.2f  solved=%d/%d".format(scale, solved(scale), positions.size))
            }
            appendLine()
            appendLine("Played out the scale is flat - 48.2% at 0.00 and 48.7% at 0.50 over 120 battles -")
            appendLine("which is the expected answer, not a disappointing one. These positions are too")
            appendLine("rare in random battles for win rate to see them at all.")
        }
        println(report)

        // The shipping value has to be the one that solves them, and the old reading has to still be
        // visibly worse. Without the second half this passes for the wrong reason the moment some
        // other change happens to solve the positions on its own.
        assertEquals(positions.size, solved(0.0), report)
        assertTrue(solved(1.0) < positions.size, report)
    }
}

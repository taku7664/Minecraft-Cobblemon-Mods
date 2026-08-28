package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfiles
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Prices making the knockout conditional on getting there first.
 *
 * `actsFirstProbability` now reaches the ranking, which is a bug fix - the field existed and nothing
 * filled it. Spending it is a design change, so it ships at zero and is decided here rather than by
 * argument. A knockout landed before the reply ends the exchange; the same knockout landed after it
 * only wins the trade, and how much less that is worth is not obvious from the armchair.
 */
class LocalFirstStrikeWeightTest {
    @Test
    fun `first strike weight is swept against the foresight positions and played out`() {
        val positions = LocalForesightPositions.reachable()
        val profile = BattleTrainerProfile(
            skillLevel = 2,
            personality = BattleTrainerProfile.champion().personality,
            difficulty = BattleDifficultyProfiles.BOSS,
        )
        val weights = listOf(0.0, 0.25, 0.5, 0.75, 1.0)

        val report = buildString {
            appendLine("=".repeat(96))
            appendLine("FIRST STRIKE WEIGHT  positions=${positions.size}")
            appendLine("=".repeat(96))
            weights.forEach { weight ->
                val tuning = LocalDecisionTuning.CURRENT.copy(id = "fs$weight", firstStrikeWeight = weight)
                val solved = positions.count { position ->
                    LocalDecisionInstrumentation.inspect(
                        context = PublicBattleTacticalCalculator.calculate(position.context),
                        profile = profile,
                        tuning = tuning,
                    ).candidates.maxByOrNull { it.comparisonValue }?.actionId == position.patientAction
                }
                val duel = LocalSelfPlayMeasurement.headToHead(
                    label = "firstStrike=$weight",
                    challenger = tuning,
                    defender = LocalDecisionTuning.CURRENT.copy(id = "fs_off"),
                    battles = BATTLES,
                    seed = SEED,
                )
                appendLine(
                    "  weight=%.2f  foresight=%d/%d  %s".format(
                        weight, solved, positions.size, duel.row(),
                    ),
                )
            }
            appendLine()
            appendLine("Zero against zero is the control: it has to land near 50% or the harness is")
            appendLine("measuring noise rather than the knob.")
        }
        println(report)

        assertTrue(positions.isNotEmpty(), report)
    }

    private companion object {
        const val BATTLES = 40
        const val SEED = 20260828
    }
}

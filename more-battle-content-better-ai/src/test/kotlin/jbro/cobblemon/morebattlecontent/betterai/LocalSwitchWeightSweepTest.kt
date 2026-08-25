package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * Sweeps the one weight the unit rewrite could not derive.
 *
 * Every other constant has a defensible origin: knockout material is what a living Pokemon is worth,
 * pressure is the HP fraction removed, survival is turns. The switch exposure weight is different -
 * exposure is damage *per turn*, so converting it to a score means committing to how many turns the
 * improved matchup is expected to last, and that is an empirical question about how battles actually
 * go, not something to settle by argument.
 *
 * Legacy scored a 2x to 1x escape at 50 points on a type-multiplier scale. On the HP-fraction scale
 * the same escape is 0.25, so the weight is literally the horizon in turns times 100.
 */
class LocalSwitchWeightSweepTest {
    @Test
    @EnabledIfSystemProperty(
        named = "betterai.sweeps",
        matches = "true",
        disabledReason = "Parameter sweeps run hundreds of battles; they are a calibration tool, " +
            "not a regression gate. Run with -Dbetterai.sweeps=true when a weight is in question.",
    )
    fun `switch exposure weight is swept against battle outcomes`() {
        val candidates = listOf(50.0, 100.0, 150.0, 200.0, 300.0)
        val rows = candidates.map { weight ->
            val tuning = LocalDecisionTuning.CURRENT.copy(
                id = "w$weight",
                switchExposureImprovementWeight = weight,
                switchExposureWorseningWeight = weight,
            )
            val mirror = LocalSelfPlayMeasurement.mirror(
                label = String.format("weight=%-5.0f (%.1f turn horizon)", weight, weight / 100.0),
                tuning = tuning,
                battles = BATTLES,
                seed = SEED,
            )
            val duel = LocalSelfPlayMeasurement.headToHead(
                label = "weight=$weight vs legacy",
                challenger = tuning,
                defender = LocalDecisionTuning.LEGACY,
                battles = BATTLES,
                seed = SEED,
            )
            Triple(weight, mirror, duel)
        }

        val report = buildString {
            appendLine("=".repeat(112))
            appendLine("SWITCH EXPOSURE WEIGHT SWEEP  seed=$SEED battles=$BATTLES")
            appendLine("=".repeat(112))
            appendLine("weight / 100 is the horizon in turns the improved matchup is assumed to hold.")
            appendLine()
            rows.forEach { (_, mirror, duel) ->
                appendLine(mirror.row())
                appendLine("    " + duel.row())
            }
            appendLine()
            appendLine("Read switch/battle as churn and decisive/stall as whether battles resolve.")
            appendLine("share is the only strength signal; anything near 50% means the weight is not")
            appendLine("what decides games at this sample size.")
        }
        println(report)

        assertTrue(rows.size == candidates.size, report)
    }

    private companion object {
        const val BATTLES = 30
        const val SEED = 20260825
    }
}

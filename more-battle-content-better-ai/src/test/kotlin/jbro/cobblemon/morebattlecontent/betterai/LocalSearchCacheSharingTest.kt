package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfiles
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.morebattlecontent.betterai.search.LocalRecursiveLookaheadEvaluator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Prices what structural cache keys are worth against the identity keys they replaced.
 *
 * Leaf evaluation is the most expensive thing in the search: for every state it reaches, it runs a
 * full public tactical calculation for every damaging move on both sides. That work was cached by the
 * state *object*, and projection allocates a fresh object every time, so two positions identical in
 * every respect the calculation depends on shared nothing. The search's own value memo already keyed
 * structurally, which is how it was visible that the sharing was there to be had.
 *
 * The counter is kept rather than the conclusion: if some later change stops states from converging,
 * this stops reporting a saving instead of quietly costing one.
 */
class LocalSearchCacheSharingTest {
    @Test
    fun `structural cache keys are measured against identity keys`() {
        val contexts = recordPositions()
        val tiers = listOf(
            "standard" to BattleDifficultyProfiles.STANDARD,
            "advanced" to BattleDifficultyProfiles.ADVANCED,
            "boss" to BattleDifficultyProfiles.BOSS,
        )

        var totalPerformed = 0L
        var totalUnderIdentity = 0L
        val report = buildString {
            appendLine("=".repeat(96))
            appendLine("LEAF CALCULATION SHARING  positions=${contexts.size}")
            appendLine("=".repeat(96))
            appendLine("performed = tactical calculations the leaf evaluations actually ran")
            appendLine("identity  = what the same searches would have run keyed by state object")
            appendLine()
            tiers.forEach { (name, difficulty) ->
                val profile = BattleTrainerProfile(
                    skillLevel = 2,
                    personality = BattleTrainerProfile.champion().personality,
                    difficulty = difficulty,
                )
                var performed = 0L
                var underIdentity = 0L
                contexts.forEach { context ->
                    val calculated = PublicBattleTacticalCalculator.calculate(context)
                    val base = LocalBattleActionPolicy.rank(calculated, null, profile, LocalDecisionTuning.CURRENT)
                    val evaluation = LocalRecursiveLookaheadEvaluator.evaluate(
                        base, calculated, profile, LocalDecisionTuning.CURRENT,
                    )
                    performed += evaluation.leafCalculations
                    underIdentity += evaluation.leafCalculationsUnderIdentityKeying
                }
                totalPerformed += performed
                totalUnderIdentity += underIdentity
                val saved = if (underIdentity == 0L) 0.0 else (underIdentity - performed) * 100.0 / underIdentity
                appendLine(
                    "  %-10s performed=%-10d identity=%-10d saved=%5.1f%%".format(
                        name, performed, underIdentity, saved,
                    ),
                )
            }
            appendLine()
            appendLine("A saving near zero would mean projected states almost never coincide, and the")
            appendLine("structural key is paying for a fingerprint it never cashes in.")
        }
        println(report)

        assertTrue(totalPerformed <= totalUnderIdentity, report)
        assertTrue(totalUnderIdentity > 0, report)
    }

    private fun recordPositions(): List<BattleDecisionContext> {
        val recorded = mutableListOf<BattleDecisionContext>()
        LocalSelfPlayMeasurement.definitions(BATTLES, SEED).forEach { definition ->
            LocalTacticalScenarioBattle.run(definition, maximumTurns = 20, recordedContexts = recorded)
        }
        return recorded.filter { it.candidates.size > 1 }.take(POSITION_LIMIT)
    }

    private companion object {
        const val BATTLES = 4
        const val POSITION_LIMIT = 20
        const val SEED = 20260825
    }
}

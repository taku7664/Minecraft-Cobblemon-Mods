package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.morebattlecontent.betterai.search.LocalRecursiveLookaheadEvaluator
import org.junit.jupiter.api.Test

/**
 * Whether the doubles search is worth what it costs.
 *
 * Depth 1.08 with 92% of positions truncated, for about 200ms of server thread per decision, invites
 * an obvious question: if it barely finishes a turn, does it change the answer at all? A search that
 * changes nothing is not a weak search, it is a bill.
 *
 * The only honest test is the one the plan already uses for depth in singles - compare the action the
 * flat heuristic would have chosen against the action production actually chooses, and count how
 * often they differ. Win rate cannot answer this at any sample size a test can afford (principle 7).
 */
class LocalDoublesSearchWorthTest {
    @Test
    fun `report how often the search changes a doubles decision`() {
        listOf(BattleFormat.DOUBLE, BattleFormat.SINGLE).forEach { format ->
            val positions = recordPositions(format)
            val profile = BattleTrainerProfile.balanced(2)
            var changed = 0
            var searchMillis = 0L
            positions.forEach { context ->
                val calculated = PublicBattleTacticalCalculator.calculate(context)
                val base = LocalBattleActionPolicy.rank(calculated, null, profile, LocalDecisionTuning.CURRENT)
                val heuristicChoice = base.maxByOrNull { it.comparisonValue }?.outcome?.candidate?.actionId
                val startedAt = System.nanoTime()
                val evaluation = LocalRecursiveLookaheadEvaluator.evaluate(
                    ranked = base,
                    context = calculated,
                    profile = profile,
                    tuning = LocalDecisionTuning.CURRENT,
                )
                searchMillis += (System.nanoTime() - startedAt) / 1_000_000
                val searchedChoice = evaluation.ranked.maxByOrNull { it.comparisonValue }
                    ?.outcome?.candidate?.actionId
                if (heuristicChoice != searchedChoice) changed++
            }
            val n = positions.size.coerceAtLeast(1)
            println(
                String.format(
                    "%-7s n=%-3d  search changes the answer in %5.1f%% of positions  cost=%5.1f ms/decision",
                    format.name.lowercase(), positions.size,
                    changed.toDouble() / n * 100, searchMillis.toDouble() / n,
                ),
            )
        }
    }

    private fun recordPositions(format: BattleFormat): List<BattleDecisionContext> {
        val recorded = mutableListOf<BattleDecisionContext>()
        LocalSelfPlayMeasurement.definitions(4, 20260829, format).forEach { definition ->
            LocalTacticalScenarioBattle.run(definition, maximumTurns = 12, recordedContexts = recorded)
        }
        return recorded.filter { it.candidates.size > 1 }.take(40)
    }
}

package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfiles
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.morebattlecontent.betterai.search.LocalLookaheadBudget
import jbro.cobblemon.morebattlecontent.betterai.search.LocalRecursiveLookaheadEvaluator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Prices the Boss search budget in the only currency that matters: decisions it changes.
 *
 * A time budget is not free. It is wall clock on the server thread, paid on every Boss decision, and
 * a player waits through it. Three seconds was chosen without ever being measured against what it
 * bought, and the tier divergence measurement then showed Boss and Advanced picking the identical
 * action in 40 of 40 recorded positions despite Boss genuinely reaching a deeper mean depth. Depth was
 * being reached and then converging on the same answer.
 *
 * That measurement compared two *tiers*, which differ in ply allowance as well as in budget, so it
 * could not settle the budget question on its own. This one holds the tier fixed and varies only the
 * budget, which is the actual question: at Boss's own ply allowance, does the extra time change any
 * decision?
 */
class LocalSearchBudgetTest {
    @Test
    fun `the boss time budget is measured against the decisions it changes`() {
        val contexts = recordPositions()
        val profile = BattleTrainerProfile(
            skillLevel = 2,
            personality = BattleTrainerProfile.champion().personality,
            difficulty = BattleDifficultyProfiles.BOSS,
        )
        val budgets = listOf(
            "former (3000ms)" to LocalLookaheadBudget(3_000L, 400_000, 64),
            "current (1500ms)" to LocalLookaheadBudget(1_500L, 400_000, 64),
            "half again (750ms)" to LocalLookaheadBudget(750L, 400_000, 64),
        )

        val resultsByBudget = budgets.associate { (name, budget) ->
            name to contexts.map { context -> decide(context, profile, budget) }
        }

        val report = buildString {
            appendLine("=".repeat(96))
            appendLine("BOSS SEARCH BUDGET  positions=${contexts.size}  plies=${profile.difficulty.lookaheadPlies}")
            appendLine("=".repeat(96))
            appendLine("Only the budget varies. Divergence is measured against the former 3000ms budget,")
            appendLine("so it reads directly as 'decisions this cut would have changed'.")
            appendLine()
            val reference = resultsByBudget.getValue(budgets.first().first)
            budgets.forEach { (name, budget) ->
                val results = resultsByBudget.getValue(name)
                val differing = results.indices.count { results[it].actionId != reference[it].actionId }
                val depths = results.map { it.depth }
                val histogram = depths.groupingBy { it }.eachCount().toSortedMap()
                    .entries.joinToString(" ") { "d${it.key}x${it.value}" }
                appendLine(
                    String.format(
                        "  %-20s %5dms  divergence=%5.1f%% (%d/%d)  depth mean=%.2f  %s",
                        name, budget.timeMillis,
                        differing * 100.0 / contexts.size, differing, contexts.size,
                        depths.average(), histogram,
                    ),
                )
            }
            appendLine()
            appendLine("Divergence at the current budget is the cost of the cut. Divergence at 750ms is")
            appendLine("the headroom left: if it is also zero, the budget is still not the binding limit")
            appendLine("and the search is converging long before the clock stops it.")
        }
        println(report)

        val reference = resultsByBudget.getValue(budgets.first().first)
        val current = resultsByBudget.getValue(budgets[1].first)
        val changed = reference.indices.count { reference[it].actionId != current[it].actionId }
        assertTrue(contexts.isNotEmpty(), report)
        // The cut is justified by decisions preserved, not by the reasoning that led to it. If a
        // future change makes the extra time matter, this is where it surfaces.
        assertTrue(
            changed * 1.0 / contexts.size <= MAXIMUM_TOLERATED_DIVERGENCE,
            "Halving the Boss budget changed $changed of ${contexts.size} decisions\n$report",
        )
    }

    private data class BudgetDecision(val actionId: String, val depth: Int)

    private fun decide(
        context: BattleDecisionContext,
        profile: BattleTrainerProfile,
        budget: LocalLookaheadBudget,
    ): BudgetDecision {
        // The same two production stages the Brain runs, so a budget is never measured against a
        // reimplementation of the ranking it is supposed to affect.
        val calculated = PublicBattleTacticalCalculator.calculate(context)
        val base = LocalBattleActionPolicy.rank(calculated, null, profile, LocalDecisionTuning.CURRENT)
        val evaluation = LocalRecursiveLookaheadEvaluator.evaluate(
            ranked = base,
            context = calculated,
            profile = profile,
            tuning = LocalDecisionTuning.CURRENT,
            budget = budget,
        )
        val best = evaluation.ranked.maxByOrNull { it.comparisonValue }
        return BudgetDecision(best?.outcome?.candidate?.actionId ?: "none", evaluation.depthCompleted)
    }

    /** Real positions from played battles, shared with the tier measurement so the two are comparable. */
    private fun recordPositions(): List<BattleDecisionContext> {
        val recorded = mutableListOf<BattleDecisionContext>()
        LocalSelfPlayMeasurement.definitions(BATTLES, SEED).forEach { definition ->
            LocalTacticalScenarioBattle.run(definition, maximumTurns = 20, recordedContexts = recorded)
        }
        return recorded.filter { it.candidates.size > 1 }.take(POSITION_LIMIT)
    }

    private companion object {
        const val BATTLES = 4
        const val POSITION_LIMIT = 40
        const val SEED = 20260825
        /**
         * Two positions in forty.
         *
         * The measured cost of the cut is one, and one is also the resolution of this sample - a
         * threshold set exactly there would fail on any tie broken the other way rather than on a
         * real regression. Two is the smallest bound that distinguishes "the cut costs about what it
         * was measured to cost" from "something changed".
         */
        const val MAXIMUM_TOLERATED_DIVERGENCE = 0.05
    }
}

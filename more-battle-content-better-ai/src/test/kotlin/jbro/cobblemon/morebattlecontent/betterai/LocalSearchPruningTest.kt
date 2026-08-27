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
 * Reports how much of the search is actually being skipped.
 *
 * `LocalTurnBranchPruner` exists and is wired in, but its thresholds only stop a continuation when
 * the turn already lost close to a full health bar of board value - `-0.85` at one ply remaining,
 * down to `-1.30` deeper. That catches catastrophes. It does not catch the case the pruning was
 * asked for: a line that is simply pointless, where the turn delta sits near zero and every
 * continuation is explored anyway.
 *
 * Nothing measured the difference, so this does. If the pruned share is small, the search is paying
 * full price for lines it could have abandoned, and the cost lands on the server thread every turn
 * and on this suite every run.
 */
class LocalSearchPruningTest {
    @Test
    fun `pruning is measured as a share of the search actually performed`() {
        val contexts = recordPositions()
        val tiers = listOf(
            "introductory" to BattleDifficultyProfiles.INTRODUCTORY,
            "standard" to BattleDifficultyProfiles.STANDARD,
            "advanced" to BattleDifficultyProfiles.ADVANCED,
            "boss" to BattleDifficultyProfiles.BOSS,
        )

        val report = buildString {
            appendLine("=".repeat(100))
            appendLine("SEARCH PRUNING  positions=${contexts.size}")
            appendLine("=".repeat(100))
            appendLine("nodes    = states the search actually evaluated")
            appendLine("pruned   = continuations abandoned by LocalTurnBranchPruner")
            appendLine("share    = pruned / (pruned + nodes), the fraction of work avoided")
            appendLine()
            tiers.forEach { (name, difficulty) ->
                val profile = BattleTrainerProfile(
                    skillLevel = 2,
                    personality = BattleTrainerProfile.champion().personality,
                    difficulty = difficulty,
                )
                var nodes = 0L
                var pruned = 0L
                contexts.forEach { context ->
                    val calculated = PublicBattleTacticalCalculator.calculate(context)
                    val base = LocalBattleActionPolicy.rank(calculated, null, profile, LocalDecisionTuning.CURRENT)
                    val evaluation = LocalRecursiveLookaheadEvaluator.evaluate(
                        base, calculated, profile, LocalDecisionTuning.CURRENT,
                    )
                    nodes += evaluation.nodesVisited
                    pruned += evaluation.branchesPruned
                }
                val share = if (nodes + pruned == 0L) 0.0 else pruned * 100.0 / (nodes + pruned)
                appendLine(
                    "  %-14s nodes=%-10d pruned=%-8d share=%5.2f%%   nodes/decision=%.0f".format(
                        name, nodes, pruned, share, nodes.toDouble() / contexts.size,
                    ),
                )
            }
            appendLine()
            appendLine("A share near zero means the pruner only ever fires on disasters, and every")
            appendLine("pointless line is still searched to full depth.")
        }
        println(report)

        assertTrue(contexts.isNotEmpty(), report)
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
        const val POSITION_LIMIT = 40
        const val SEED = 20260825
    }
}

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
            appendLine("leaf     = the share of nodes spent scoring leaves rather than projecting turns")
            appendLine()
            tiers.forEach { (name, difficulty) ->
                val profile = BattleTrainerProfile(
                    skillLevel = 2,
                    personality = BattleTrainerProfile.champion().personality,
                    difficulty = difficulty,
                )
                var nodes = 0L
                var pruned = 0L
                // The node budget is spent by two different things. Projecting a turn is the tree work
                // the limit is named for; scoring a leaf recalculates every damaging move on both sides
                // and charges the same counter. A tier's allowance is therefore not all going to depth,
                // and until this was split nobody knew how much of it was not.
                var leafWork = 0L
                // Budget exhaustion does not merely stop early - inside a node it abandons the
                // remaining own-actions in list order, so which moves get considered at all is decided
                // by catalog position rather than by promise.
                var truncated = 0
                contexts.forEach { context ->
                    val calculated = PublicBattleTacticalCalculator.calculate(context)
                    val base = LocalBattleActionPolicy.rank(calculated, null, profile, LocalDecisionTuning.CURRENT)
                    val evaluation = LocalRecursiveLookaheadEvaluator.evaluate(
                        base, calculated, profile, LocalDecisionTuning.CURRENT,
                    )
                    nodes += evaluation.nodesVisited
                    pruned += evaluation.branchesPruned
                    leafWork += evaluation.leafWorkUnits
                    if (evaluation.truncated) truncated++
                }
                val share = if (nodes + pruned == 0L) 0.0 else pruned * 100.0 / (nodes + pruned)
                val leafShare = if (nodes == 0L) 0.0 else leafWork * 100.0 / nodes
                appendLine(
                    ("  %-14s nodes=%-10d pruned=%-8d share=%5.2f%%   nodes/decision=%-8.0f " +
                        "leaf=%5.1f%% truncated=%d/%d").format(
                        name, nodes, pruned, share, nodes.toDouble() / contexts.size,
                        leafShare, truncated, contexts.size,
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

    @Test
    fun `pruning harder is measured against the foresight it would cost`() {
        // The request behind this was "stop going deeper into useless branches". The pruner decides
        // useless by the immediate turn delta, and that is exactly what a turn-order or survival play
        // looks like on the turn it is played: a weak-looking move whose whole value is next turn.
        //
        // So the two things asked for pull against each other, and the question is where the trade
        // sits rather than whether to make it. This prices both sides at once - what each threshold
        // saves, and which of the foresight positions it stops solving.
        val contexts = recordPositions()
        val profile = BattleTrainerProfile(
            skillLevel = 2,
            personality = BattleTrainerProfile.champion().personality,
            difficulty = BattleDifficultyProfiles.BOSS,
        )
        val offsets = listOf(0.0, 0.40, 0.70, 0.85)

        val report = buildString {
            appendLine("=".repeat(104))
            appendLine("PRUNING HARDER, AND WHAT IT COSTS  positions=${contexts.size}")
            appendLine("=".repeat(104))
            appendLine("offset raises the bar for abandoning a continuation, in board units. At 0.85 the")
            appendLine("pruner stops any branch that did not gain on the turn it was played.")
            appendLine()
            offsets.forEach { offset ->
                val tuning = LocalDecisionTuning.CURRENT.copy(
                    id = "prune$offset", branchPruneThresholdOffset = offset,
                )
                var nodes = 0L
                var pruned = 0L
                contexts.forEach { context ->
                    val calculated = PublicBattleTacticalCalculator.calculate(context)
                    val base = LocalBattleActionPolicy.rank(calculated, null, profile, tuning)
                    val evaluation = LocalRecursiveLookaheadEvaluator.evaluate(base, calculated, profile, tuning)
                    nodes += evaluation.nodesVisited
                    pruned += evaluation.branchesPruned
                }
                val solved = LocalForesightPositions.reachable().count { position ->
                    val breakdown = LocalDecisionInstrumentation.inspect(
                        context = PublicBattleTacticalCalculator.calculate(position.context),
                        profile = profile,
                        tuning = tuning,
                    )
                    breakdown.candidates.maxByOrNull { it.comparisonValue }?.actionId == position.patientAction
                }
                appendLine(
                    "  offset=%.2f  nodes=%-10d pruned=%-8d  foresight solved=%d/%d".format(
                        offset, nodes, pruned, solved, LocalForesightPositions.reachable().size,
                    ),
                )
            }
            appendLine()
            appendLine("A threshold that saves real work and keeps every foresight position is worth")
            appendLine("taking. One that trades them away is the request answered by breaking the other")
            appendLine("request.")
        }
        println(report)

        assertTrue(contexts.isNotEmpty(), report)
    }

    @Test
    fun `pruning harder is measured against the battles it produces`() {
        // Pruning more turned out not to cost any foresight position, and not to save any nodes
        // either: the total is set by the node and time budget, and freeing work inside it only lets
        // iterative deepening spend the same allowance further down. So the trade is not
        // work-against-capability but depth-against-breadth, and only played battles can price that.
        val shipping = LocalDecisionTuning.CURRENT.copy(id = "prune_off")
        val harder = LocalDecisionTuning.CURRENT.copy(id = "prune_hard", branchPruneThresholdOffset = 0.70)
        val duel = LocalSelfPlayMeasurement.headToHead(
            label = "harder pruning vs shipping",
            challenger = harder,
            defender = shipping,
            battles = BATTLES_PLAYED,
            seed = SEED,
        )

        val report = buildString {
            appendLine("=".repeat(104))
            appendLine("HARDER PRUNING, PLAYED OUT  battles=$BATTLES_PLAYED")
            appendLine("=".repeat(104))
            appendLine(duel.row())
            appendLine()
            appendLine("Below 50% means the abandoned branches were carrying information after all, and")
            appendLine("the deeper search bought with them did not replace it.")
        }
        println(report)

        assertTrue(duel.challengerWins + duel.defenderWins + duel.undecided == BATTLES_PLAYED * 2, report)
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
        const val BATTLES_PLAYED = 60
    }
}

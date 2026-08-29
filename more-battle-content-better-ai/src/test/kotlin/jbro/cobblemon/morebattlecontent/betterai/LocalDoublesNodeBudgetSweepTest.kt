package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.morebattlecontent.betterai.search.LocalLookaheadBudget
import jbro.cobblemon.morebattlecontent.betterai.search.LocalRecursiveLookaheadEvaluator
import org.junit.jupiter.api.Test

/**
 * What a doubles search would do with a node allowance sized for doubles.
 *
 * The limit that binds is the node count, not the clock: a doubles position spends about 18,000
 * nodes against the 15,000 a Standard trainer is allowed, while a singles position spends 2,600 of
 * the same allowance and finishes two full plies. The allowance was measured on singles and a
 * doubles turn is four actions rather than two, so it is being asked to buy something structurally
 * more expensive with the same money.
 *
 * Raising it is only worth doing if the depth actually moves and the wall clock stays where a server
 * thread can afford it, so this reports both. It changes nothing on its own - the point is to have
 * the numbers before touching the shipped policy, which is the step that was skipped every time this
 * plan recorded a rejection.
 */
class LocalDoublesNodeBudgetSweepTest {
    @Test
    fun `report what more nodes buy a doubles position`() {
        val positions = recordPositions()
        println("doubles positions=${positions.size}")
        // (node limit, joint cap, per-slot cap). A joint cap of 999 disables narrowing entirely.
        val grid = listOf(
            Triple(15_000, 999, 99), Triple(15_000, 8, 99),
            Triple(15_000, 8, 3), Triple(15_000, 8, 4), Triple(15_000, 8, 5),
        )
        grid.forEach { (nodeLimit, rootLimit, perSlot) ->
            val profile = BattleTrainerProfile.balanced(2)
            var depth = 0
            var truncated = 0
            var nodes = 0
            var variety = 0
            val startedAt = System.nanoTime()
            positions.forEach { context ->
                val calculated = PublicBattleTacticalCalculator.calculate(context)
                val base = LocalBattleActionPolicy.rank(
                    calculated, null, profile,
                    LocalDecisionTuning.CURRENT.copy(
                        maximumRootCandidates = rootLimit,
                        maximumRootActionsPerSlot = perSlot,
                    ),
                )
                val evaluation = LocalRecursiveLookaheadEvaluator.evaluate(
                    ranked = base,
                    context = calculated,
                    profile = profile,
                    tuning = LocalDecisionTuning.CURRENT.copy(
                        maximumRootCandidates = rootLimit,
                        maximumRootActionsPerSlot = perSlot,
                    ),
                    budget = LocalLookaheadBudget(
                        timeMillis = 750L,
                        nodeLimit = nodeLimit,
                        chanceBranchesPerMove = 24,
                    ),
                )
                depth += evaluation.depthCompleted
                nodes += evaluation.nodesVisited
                if (evaluation.truncated) truncated++
                // How many different first-slot moves the search actually weighed. One means the
                // budget went entirely into re-deciding the other half of the turn.
                variety += evaluation.ranked
                    .sortedByDescending { it.comparisonValue }
                    .take(rootLimit.coerceAtMost(evaluation.ranked.size))
                    .flatMap { rank ->
                        val candidate = rank.outcome.candidate
                        if (candidate.componentActions.isEmpty()) listOf(candidate) else candidate.componentActions
                    }
                    .filter { it.actorSlot == 0 }
                    .map { it.actionId }
                    .distinct().size
            }
            val n = positions.size.coerceAtLeast(1)
            val elapsedMillisPerPosition = (System.nanoTime() - startedAt) / 1_000_000.0 / n
            println(
                String.format(
                    "nodes=%6d joint=%3d slot=%3d  depth=%4.2f  trunc=%5.1f%%  used=%7.0f  ms=%5.1f  slot0variety=%4.1f",
                    nodeLimit, rootLimit, perSlot, depth.toDouble() / n, truncated.toDouble() / n * 100,
                    nodes.toDouble() / n, elapsedMillisPerPosition, variety.toDouble() / n,
                ),
            )
        }
    }

    private fun recordPositions(): List<BattleDecisionContext> {
        val recorded = mutableListOf<BattleDecisionContext>()
        LocalSelfPlayMeasurement.definitions(3, 20260829, BattleFormat.DOUBLE).forEach { definition ->
            LocalTacticalScenarioBattle.run(definition, maximumTurns = 12, recordedContexts = recorded)
        }
        return recorded.filter { it.candidates.size > 1 }.take(24)
    }
}

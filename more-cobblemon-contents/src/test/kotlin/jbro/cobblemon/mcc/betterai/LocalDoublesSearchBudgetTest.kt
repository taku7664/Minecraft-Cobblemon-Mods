package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.mcc.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.mcc.betterai.search.LocalRecursiveLookaheadEvaluator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards a complete current-turn doubles search over recorded self-play positions.
 *
 * Doubles retains joint choices from four actions per slot plus cooperative leaders. Applying the
 * singles node allowance to that Cartesian product previously cut seven of these 24 positions
 * before their first ply completed (mean depth 0.71). Doubles now resolves one ply without a node
 * ceiling while preserving the ten-second clock deadline and existing candidate/chance limits.
 * Singles keeps its tier node allowance and configured depth. This measurement reports both paths
 * without treating a partially evaluated doubles root as a completed turn.
 */
class LocalDoublesSearchBudgetTest {
    @Test
    fun `a doubles position is searched to a usable answer`() {
        val doubles = measure(BattleFormat.DOUBLE)
        val singles = measure(BattleFormat.SINGLE)
        println(singles.row())
        println(doubles.row())
        println(
            "branching ratio=" + String.format("%.1f", doubles.meanCandidates / singles.meanCandidates) +
                "x  depth ratio=" + String.format("%.2f", doubles.meanDepth / singles.meanDepth),
        )
        assertTrue(
            doubles.positions > 0,
            "No doubles position was recorded, so nothing below this line means anything.",
        )
        assertTrue(
            doubles.meanDepth > 0.0,
            "Some search has to happen, however little: " + doubles.row(),
        )
        assertTrue(
            doubles.meanDepth >= 1.0,
            "A doubles position has to resolve at least the turn in front of it: " + doubles.row(),
        )
        assertTrue(
            doubles.separatedRate > 0.5,
            "A ranking that cannot tell its candidates apart is a coin flip wearing a search: " +
                doubles.row(),
        )
    }

    @Test
    fun `the joint action is the width it looks like`() {
        val doubles = measure(BattleFormat.DOUBLE)
        val singles = measure(BattleFormat.SINGLE)
        // Recorded as a number rather than asserted tightly. The point of writing it down is that the
        // next person to change branching can see what it used to be.
        assertTrue(
            doubles.meanCandidates > singles.meanCandidates,
            "A doubles turn pairs two slots, so it cannot be narrower than a singles one: " +
                "doubles=${doubles.meanCandidates} singles=${singles.meanCandidates}",
        )
    }

    private fun measure(format: BattleFormat): BudgetShape {
        val positions = recordPositions(format)
        val profile = BattleTrainerProfile.balanced(2)
        var candidates = 0
        var depth = 0
        var truncated = 0
        var separated = 0
        var nodes = 0
        var leafWork = 0
        positions.forEach { context ->
            val calculated = PublicBattleTacticalCalculator.calculate(context)
            val base = LocalBattleActionPolicy.rank(calculated, null, profile, LocalDecisionTuning.CURRENT)
            val evaluation = LocalRecursiveLookaheadEvaluator.evaluate(
                ranked = base,
                context = calculated,
                profile = profile,
                tuning = LocalDecisionTuning.CURRENT,
            )
            if (evaluation.depthCompleted < 1) {
                println("incomplete format=$format turn=${context.state.turn} reason=${evaluation.terminationReason}" +
                    " nodes=${evaluation.nodesVisited} partial=${evaluation.partialDepthCandidates}" +
                    " candidates=${calculated.candidates.size} remaining=${context.state.remainingPokemonBySide}")
            }
            candidates += calculated.candidates.size
            depth += evaluation.depthCompleted
            nodes += evaluation.nodesVisited
            leafWork += evaluation.leafWorkUnits
            if (evaluation.truncated) truncated++
            val values = evaluation.ranked.map { it.comparisonValue }
            if (values.distinct().size > 1) separated++
        }
        val n = positions.size.coerceAtLeast(1)
        return BudgetShape(
            label = format.name.lowercase(),
            positions = positions.size,
            meanCandidates = candidates.toDouble() / n,
            meanDepth = depth.toDouble() / n,
            truncationRate = truncated.toDouble() / n,
            separatedRate = separated.toDouble() / n,
            meanNodes = nodes.toDouble() / n,
            meanLeafWork = leafWork.toDouble() / n,
        )
    }

    private fun recordPositions(format: BattleFormat): List<BattleDecisionContext> {
        val recorded = mutableListOf<BattleDecisionContext>()
        LocalSelfPlayMeasurement.definitions(BATTLES, SEED, format).forEach { definition ->
            LocalTacticalScenarioBattle.run(definition, maximumTurns = 12, recordedContexts = recorded)
        }
        return recorded.filter { it.candidates.size > 1 }.take(POSITION_LIMIT)
    }

    private data class BudgetShape(
        val label: String,
        val positions: Int,
        val meanCandidates: Double,
        val meanDepth: Double,
        val truncationRate: Double,
        val separatedRate: Double,
        val meanNodes: Double = 0.0,
        val meanLeafWork: Double = 0.0,
    ) {
        fun row(): String = String.format(
            "%-8s n=%-3d candidates=%6.1f  depth=%4.2f  truncated=%5.1f%%  separated=%5.1f%%",
            label, positions, meanCandidates, meanDepth, truncationRate * 100, separatedRate * 100,
        ) + String.format("  nodes=%8.0f  leafWork=%8.0f", meanNodes, meanLeafWork)
    }

    private companion object {
        const val BATTLES = 3
        const val POSITION_LIMIT = 24
        const val SEED = 20260829
    }
}

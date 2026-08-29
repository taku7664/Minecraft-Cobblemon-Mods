package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.BattleDecisionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.morebattlecontent.betterai.policy.LocalBattleActionPolicy
import jbro.cobblemon.morebattlecontent.betterai.search.LocalRecursiveLookaheadEvaluator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the joint action does to the search budget.
 *
 * A doubles turn is not one decision, it is a Cartesian product of two. The factory builds it as
 * exactly that, with no cap: each slot offers its moves times their target variants plus its
 * switches, and the turn is every pairing of the two. Singles asks the search to weigh about a dozen
 * actions; doubles asks it to weigh over a hundred, out of the same fixed budget.
 *
 * That is a thing to know rather than a thing to assume, and it was never measured because nothing
 * played doubles. The plan already records the shape of the risk from the singles side - "uncertainty
 * widens the branching while the budget stays fixed" - and this is that same sentence with a much
 * larger multiplier in front of it.
 *
 * The binding limit turned out to be the node allowance rather than the clock: a doubles position
 * spent about 18,000 nodes against the 15,000 a Standard trainer is given, while singles spends 2,600
 * of the same allowance and finishes two plies. Capping the root list at eight moved doubles from
 * 0.75 plies to 1.08 *and* made it cheaper, because the trainer's own move list was the one thing in
 * the search that had no cap - the opponent's replies were already limited to five a slot.
 *
 * The depth is still half of singles, and that is not the same as the search being wasted. Measured
 * separately, the doubles search changes the chosen action in 15% of positions against 5% in singles,
 * for 181ms against 51ms. A shallow search is not a useless one here: doubles is exactly where the
 * flat heuristic has the most to miss - the partner in the blast, a redirect, two attacks landing on
 * one target - and a single resolved turn catches those.
 *
 * So this reports and guards a floor rather than asserting a depth. The remaining gap to singles is
 * structural: a doubles turn is four actions and its ply costs roughly twenty times a singles one.
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

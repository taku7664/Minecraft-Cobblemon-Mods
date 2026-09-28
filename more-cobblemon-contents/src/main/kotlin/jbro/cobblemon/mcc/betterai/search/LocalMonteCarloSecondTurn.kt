package jbro.cobblemon.mcc.betterai.search

import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.betterai.state.RecursiveActionHistory

/** Which search looks past the first turn. */
internal enum class LocalSearchStrategy {
    /** Full-width turns, the opponent's replies weighed toward the worst ([LocalNarrowSecondTurn] in doubles). */
    MINIMAX,
    /** [LocalMonteCarloSecondTurn]. */
    MONTE_CARLO,
}

/**
 * The second turn by sampling instead of full width.
 *
 * Every iteration plays one line two turns deep and adds its per-turn value to the root action it started
 * with; the root action's value is the average of the lines through it, starting from its first-turn value.
 * - The root action is chosen optimistically (UCB), so the promising ones are played most.
 * - The opponent's first reply is chosen pessimistically: the reply that has hurt this action most so far,
 *   led by the intent prediction's chance. A single punishing reply is found and played again, so it is
 *   not averaged away.
 * - The first turn's chance (misses, knockouts) is drawn by its probability; the second turn is resolved
 *   exactly, over its own chance.
 * - In the second turn the AI picks optimistically and the opponent pessimistically in the same way, among
 *   [INNER_OWN_PER_SLOT] and [INNER_OPPONENT_PER_SLOT] candidates a slot.
 *
 * It never discards work: whatever the budget allows is averaged in, and an action played [MINIMUM_VISITS]
 * times or more takes its average.
 */
internal object LocalMonteCarloSecondTurn {
    const val ROOT_CANDIDATES = 6
    const val INNER_OWN_PER_SLOT = 3
    const val INNER_OPPONENT_PER_SLOT = 6
    const val MINIMUM_VISITS = 3
    /** Exploration, in units of the spread of the first-turn values. */
    private const val EXPLORATION = 1.0
    private const val MINIMUM_SPREAD = 0.02

    class Outcome(val weight: Double, val state: BattleStateView, val history: RecursiveActionHistory)

    /** Statistics that start from a first-turn value counted as one visit. */
    open class Arm(val prior: Double) {
        var visits = 0
            private set
        private var total = 0.0
        val mean: Double get() = (prior + total) / (1 + visits)

        fun add(value: Double) {
            visits++
            total += value
        }
    }

    class Reply(val index: Int, prior: Double, val chance: Double) : Arm(prior) {
        var dead = false
    }

    class RootArm(val action: BattleActionCandidate, prior: Double, val replies: List<Reply>) : Arm(prior) {
        val outcomes = HashMap<Int, List<Outcome>>()
        val dead: Boolean get() = replies.all { it.dead }
    }

    class InnerNode(
        val state: BattleStateView,
        val start: Double,
        val history: RecursiveActionHistory,
        val own: List<BattleActionCandidate>,
        val opponent: List<BattleActionCandidate>,
        /** A value that needs no sampling: a forced replacement resolved exactly, or no actions left. */
        val exact: Double?,
    ) {
        val ownArms = own.map { Arm(0.0) }
        val opponentArms = own.map { opponent.map { Arm(0.0) } }
        var visits = 0
    }

    fun spread(values: List<Double>): Double {
        if (values.size < 2) return MINIMUM_SPREAD
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size).coerceAtLeast(MINIMUM_SPREAD)
    }

    fun selectRoot(arms: List<RootArm>, iteration: Int, spread: Double): RootArm = arms.filterNot { it.dead }.maxBy {
        it.mean + EXPLORATION * spread * sqrt(ln(iteration + 1.0) / (it.visits + 1))
    }

    fun selectReply(arm: RootArm, spread: Double): Reply = arm.replies.filterNot { it.dead }.minBy {
        it.mean - EXPLORATION * spread * (it.chance + 1.0 / arm.replies.size) * sqrt(arm.visits + 1.0) / (1 + it.visits)
    }

    /** Index of the arm to play: unplayed first, then the one the bound favours. */
    fun selectInner(arms: List<Arm>, visits: Int, spread: Double, maximize: Boolean): Int {
        arms.indexOfFirst { it.visits == 0 }.takeIf { it >= 0 }?.let { return it }
        val bonus = { arm: Arm -> EXPLORATION * spread * sqrt(ln(visits + 1.0) / arm.visits) }
        return if (maximize) arms.indices.maxBy { arms[it].mean + bonus(arms[it]) }
        else arms.indices.minBy { arms[it].mean - bonus(arms[it]) }
    }

    fun sample(outcomes: List<Outcome>, random: Random): Outcome {
        val total = outcomes.sumOf { it.weight }
        var draw = random.nextDouble() * total
        for (outcome in outcomes) {
            draw -= outcome.weight
            if (draw <= 0.0) return outcome
        }
        return outcomes.last()
    }
}

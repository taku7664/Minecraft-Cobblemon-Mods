package jbro.cobblemon.mcc.betterai.search

import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier

internal data class LocalLookaheadBudget(
    val timeMillis: Long,
    val nodeLimit: Int,
    val chanceBranchesPerMove: Int,
    /**
     * How many opponent replies a deeper root iteration follows past the first turn, in the order the
     * previous iteration scored them worst for this side. Null follows every reply.
     */
    val opponentResponseLimit: Int? = null,
    /** Whether this side's last simulated turn considers only its damaging moves. */
    val finalPlyAttacksOnly: Boolean = false,
    /**
     * The native search's node budget. Its nodes are whole Showdown turns, far dearer than the legacy
     * projector's, so the two do not share [nodeLimit]; unset, it is [nodeLimit].
     */
    val nativeNodeLimit: Int = nodeLimit,
    /** Turns the native search plays; null keeps the difficulty's own horizon. */
    val nativePlies: Int? = null,
) {
    init {
        require(opponentResponseLimit == null || opponentResponseLimit > 0)
        require(nativeNodeLimit > 0)
        require(nativePlies == null || nativePlies > 0)
    }
}

/**
 * Keeps local search responsive without changing the independent Router timeout.
 *
 * The work runs on a bounded AI worker but still competes for CPU and allocation bandwidth. Wall-clock
 * completion depends on host load, so time is only a fail-safe ceiling, not a reproducible quality
 * contract. Node and chance-branch limits are the deterministic work bounds; tests verify those values
 * and distinguish node exhaustion from deadline exhaustion. Stable-decision and predicted-cost exits
 * may stop earlier without weakening either hard limit.
 */
internal object LocalLookaheadBudgetPolicy {
    /**
     * A safety ceiling only, the same for every tier: the native node budgets decide how far a decision sees.
     * Under a 6 s clock the depth followed the host's load instead; a real Boss battle saw 760 to 3,332 nodes
     * a turn where finishing two turns took 22,782 to 33,685.
     */
    const val MAX_TIME_MILLIS = 40_000L

    /**
     * Every tier searches two turns on the Boss node budget; the tiers differ in breadth instead. A one-turn
     * horizon could not tell Protect from an attack when neither changed the board this turn, so a lower tier
     * protected again and again. The narrower tiers follow fewer of the opponent's replies and only attack on
     * their own second turn, which is where they miss what a Boss sees.
     */
    fun forTier(tier: BattleTrainerTier): LocalLookaheadBudget = when (tier) {
        BattleTrainerTier.INTRODUCTORY -> LocalLookaheadBudget(
            timeMillis = MAX_TIME_MILLIS,
            nodeLimit = NODE_LIMIT,
            nativeNodeLimit = 2_500,
            chanceBranchesPerMove = 16,
            opponentResponseLimit = 2,
            finalPlyAttacksOnly = true,
        )
        BattleTrainerTier.STANDARD -> LocalLookaheadBudget(
            timeMillis = MAX_TIME_MILLIS,
            nodeLimit = NODE_LIMIT,
            nativeNodeLimit = 5_000,
            chanceBranchesPerMove = 24,
            opponentResponseLimit = 3,
            finalPlyAttacksOnly = true,
        )
        BattleTrainerTier.ADVANCED -> LocalLookaheadBudget(
            timeMillis = MAX_TIME_MILLIS,
            nodeLimit = NODE_LIMIT,
            nativeNodeLimit = 10_000,
            chanceBranchesPerMove = 40,
            opponentResponseLimit = 5,
        )
        BattleTrainerTier.BOSS -> LocalLookaheadBudget(
            timeMillis = MAX_TIME_MILLIS,
            nodeLimit = NODE_LIMIT,
            // 2026-10-10: half of 20,000 kept the choice in 29 of 32 recorded Boss positions at 30% less time.
            nativeNodeLimit = 10_000,
            chanceBranchesPerMove = 64,
        )
    }

    const val NODE_LIMIT = 400_000

    /**
     * More search where few Pokemon are left. The tree is small there and each choice decides the battle,
     * while an opening with a full board leans on the choosing rules. Only the node limit grows; the
     * wall-clock ceiling stays [MAX_TIME_MILLIS].
     */
    fun forPosition(budget: LocalLookaheadBudget, state: BattleStateView): LocalLookaheadBudget {
        if (budget.nodeLimit == Int.MAX_VALUE) return budget
        val remaining = state.remainingPokemonBySide.values.sum()
        val factor = when {
            remaining <= ENDGAME_REMAINING -> ENDGAME_NODE_FACTOR
            remaining <= LATE_REMAINING -> LATE_NODE_FACTOR
            else -> 1
        }
        return if (factor == 1) budget else budget.copy(nodeLimit = budget.nodeLimit * factor)
    }

    /**
     * With two or fewer Pokemon of its own left, the native search sees a third turn on a larger node budget:
     * the tree is small by then and each choice decides the battle. Twice the nodes with two left, three
     * times with one.
     */
    fun forNativePosition(budget: LocalLookaheadBudget, state: BattleStateView): LocalLookaheadBudget {
        val own = state.remainingPokemonBySide[BattleSide.ALLY] ?: return budget
        if (own > NATIVE_ENDGAME_OWN || budget.nativeNodeLimit == Int.MAX_VALUE) return budget
        val factor = if (own <= 1) ENDGAME_NODE_FACTOR else LATE_NODE_FACTOR
        return budget.copy(nativeNodeLimit = budget.nativeNodeLimit * factor, nativePlies = NATIVE_ENDGAME_PLIES)
    }

    /** Doubles resolves one turn without a node ceiling; its existing clock ceiling still applies. */
    fun forFormat(budget: LocalLookaheadBudget, format: BattleFormat): LocalLookaheadBudget =
        if (format == BattleFormat.DOUBLE) budget.copy(nodeLimit = Int.MAX_VALUE) else budget

    /** Both sides' remaining Pokemon: 2 v 1 in doubles, 1 v 2 or 2 v 1 in singles. */
    const val ENDGAME_REMAINING = 3
    const val LATE_REMAINING = 5
    const val ENDGAME_NODE_FACTOR = 3
    const val LATE_NODE_FACTOR = 2
    const val NATIVE_ENDGAME_OWN = 2
    const val NATIVE_ENDGAME_PLIES = 3

    fun deadline(startMillis: Long, externalDeadlineMillis: Long, budgetMillis: Long): Long {
        val localDeadline = if (startMillis > Long.MAX_VALUE - budgetMillis) {
            Long.MAX_VALUE
        } else {
            startMillis + budgetMillis
        }
        return minOf(localDeadline, externalDeadlineMillis)
    }
}

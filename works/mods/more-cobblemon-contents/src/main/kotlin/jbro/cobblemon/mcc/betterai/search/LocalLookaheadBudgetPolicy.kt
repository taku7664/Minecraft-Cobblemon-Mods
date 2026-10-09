package jbro.cobblemon.mcc.betterai.search

import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleFormat
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
) {
    init {
        require(opponentResponseLimit == null || opponentResponseLimit > 0)
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
     * One wall-clock ceiling for every tier; the node and chance-branch limits make the tiers differ. At
     * 1.5 s a Boss doubles search stopped before finishing a single turn in 20 of 22 decisions of a real
     * battle, so the clock, not the tier, decided how far it saw. Once level 100 teams stopped skipping the
     * search, 10 s made a Boss turn feel stalled; 6 s is the player's wait the whole decision may take.
     */
    const val MAX_TIME_MILLIS = 6_000L

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
            chanceBranchesPerMove = 16,
            opponentResponseLimit = 2,
            finalPlyAttacksOnly = true,
        )
        BattleTrainerTier.STANDARD -> LocalLookaheadBudget(
            timeMillis = MAX_TIME_MILLIS,
            nodeLimit = NODE_LIMIT,
            chanceBranchesPerMove = 24,
            opponentResponseLimit = 3,
            finalPlyAttacksOnly = true,
        )
        BattleTrainerTier.ADVANCED -> LocalLookaheadBudget(
            timeMillis = MAX_TIME_MILLIS,
            nodeLimit = NODE_LIMIT,
            chanceBranchesPerMove = 40,
            opponentResponseLimit = 5,
        )
        BattleTrainerTier.BOSS -> LocalLookaheadBudget(
            timeMillis = MAX_TIME_MILLIS,
            nodeLimit = NODE_LIMIT,
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

    /** Doubles resolves one turn without a node ceiling; its existing clock ceiling still applies. */
    fun forFormat(budget: LocalLookaheadBudget, format: BattleFormat): LocalLookaheadBudget =
        if (format == BattleFormat.DOUBLE) budget.copy(nodeLimit = Int.MAX_VALUE) else budget

    /** Both sides' remaining Pokemon: 2 v 1 in doubles, 1 v 2 or 2 v 1 in singles. */
    const val ENDGAME_REMAINING = 3
    const val LATE_REMAINING = 5
    const val ENDGAME_NODE_FACTOR = 3
    const val LATE_NODE_FACTOR = 2

    fun deadline(startMillis: Long, externalDeadlineMillis: Long, budgetMillis: Long): Long {
        val localDeadline = if (startMillis > Long.MAX_VALUE - budgetMillis) {
            Long.MAX_VALUE
        } else {
            startMillis + budgetMillis
        }
        return minOf(localDeadline, externalDeadlineMillis)
    }
}

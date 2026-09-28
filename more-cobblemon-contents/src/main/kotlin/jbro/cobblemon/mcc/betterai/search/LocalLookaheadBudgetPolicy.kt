package jbro.cobblemon.mcc.betterai.search

import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier

internal data class LocalLookaheadBudget(
    val timeMillis: Long,
    val nodeLimit: Int,
    val chanceBranchesPerMove: Int,
)

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
     * battle, so the clock, not the tier, decided how far it saw.
     */
    const val MAX_TIME_MILLIS = 10_000L

    fun forTier(tier: BattleTrainerTier): LocalLookaheadBudget = when (tier) {
        BattleTrainerTier.INTRODUCTORY -> LocalLookaheadBudget(
            timeMillis = MAX_TIME_MILLIS,
            nodeLimit = 2_000,
            chanceBranchesPerMove = 16,
        )
        BattleTrainerTier.STANDARD -> LocalLookaheadBudget(
            timeMillis = MAX_TIME_MILLIS,
            nodeLimit = 15_000,
            chanceBranchesPerMove = 24,
        )
        BattleTrainerTier.ADVANCED -> LocalLookaheadBudget(
            timeMillis = MAX_TIME_MILLIS,
            nodeLimit = 80_000,
            chanceBranchesPerMove = 40,
        )
        BattleTrainerTier.BOSS -> LocalLookaheadBudget(
            timeMillis = MAX_TIME_MILLIS,
            nodeLimit = 400_000,
            chanceBranchesPerMove = 64,
        )
    }

    /**
     * More search where few Pokemon are left. The tree is small there and each choice decides the battle,
     * while an opening with a full board leans on the choosing rules. Only the node limit grows; the
     * wall-clock ceiling stays [MAX_TIME_MILLIS].
     */
    fun forPosition(budget: LocalLookaheadBudget, state: BattleStateView): LocalLookaheadBudget {
        val remaining = state.remainingPokemonBySide.values.sum()
        val factor = when {
            remaining <= ENDGAME_REMAINING -> ENDGAME_NODE_FACTOR
            remaining <= LATE_REMAINING -> LATE_NODE_FACTOR
            else -> 1
        }
        return if (factor == 1) budget else budget.copy(nodeLimit = budget.nodeLimit * factor)
    }

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

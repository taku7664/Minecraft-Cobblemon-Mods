package jbro.cobblemon.morebattlecontent.betterai.search

import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier

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
    fun forTier(tier: BattleTrainerTier): LocalLookaheadBudget = when (tier) {
        BattleTrainerTier.INTRODUCTORY -> LocalLookaheadBudget(
            timeMillis = 250L,
            nodeLimit = 2_000,
            chanceBranchesPerMove = 16,
        )
        BattleTrainerTier.STANDARD -> LocalLookaheadBudget(
            timeMillis = 750L,
            nodeLimit = 15_000,
            chanceBranchesPerMove = 24,
        )
        BattleTrainerTier.ADVANCED -> LocalLookaheadBudget(
            timeMillis = 1_500L,
            nodeLimit = 80_000,
            chanceBranchesPerMove = 40,
        )
        // Boss shares Advanced's wall-clock ceiling but retains the largest deterministic node and
        // chance-branch limits. This prevents a longer tier-specific stall without flattening width.
        BattleTrainerTier.BOSS -> LocalLookaheadBudget(
            timeMillis = 1_500L,
            nodeLimit = 400_000,
            chanceBranchesPerMove = 64,
        )
    }

    fun deadline(startMillis: Long, externalDeadlineMillis: Long, budgetMillis: Long): Long {
        val localDeadline = if (startMillis > Long.MAX_VALUE - budgetMillis) {
            Long.MAX_VALUE
        } else {
            startMillis + budgetMillis
        }
        return minOf(localDeadline, externalDeadlineMillis)
    }
}

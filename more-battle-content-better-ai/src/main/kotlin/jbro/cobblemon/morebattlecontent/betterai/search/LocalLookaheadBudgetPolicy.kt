package jbro.cobblemon.morebattlecontent.betterai.search

import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier

internal data class LocalLookaheadBudget(
    val timeMillis: Long,
    val nodeLimit: Int,
    val chanceBranchesPerMove: Int,
)

/** Keeps local search responsive without changing the independent Router timeout. */
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
        BattleTrainerTier.BOSS -> LocalLookaheadBudget(
            timeMillis = 3_000L,
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

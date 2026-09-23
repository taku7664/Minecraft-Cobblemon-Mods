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
 * These are wall-clock costs paid by the server process for every NPC decision. The work runs on a
 * bounded AI worker, but still competes for CPU and allocation bandwidth, so a budget has to be
 * justified by decisions it changes, not by the depth it reaches.
 *
 * With production root refinement and weighted selection, repeated runs changed one or two of forty
 * recorded decisions when cutting Boss from 3,000ms to 1,500ms. Every tested budget at or below
 * 1,000ms crossed the five-percent guard (three to six changes), so 1,500ms remains the conservative
 * knee rather than the cheapest setting. Stable-decision and predicted-cost exits reduce work inside
 * that ceiling. `LocalSearchBudgetTest` re-measures this whenever these numbers are touched.
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
        // Halved from 3,000ms. The node ceiling and branch width stay above Advanced, so a Boss search
        // is still the widest one available and never explores less; only the wall clock a player
        // waits for, and that the server process pays for every Boss decision, is cut.
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

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
 * These are wall-clock costs paid on the server thread for every NPC decision, so a budget has to be
 * justified by decisions it changes, not by the depth it reaches.
 *
 * Boss at three seconds does buy real search - halving it drops the mean reached depth from 3.23 to
 * 2.73, and the number of positions finishing a fourth ply from 13 to 2. What that depth buys is one
 * changed decision in forty. The clock is a binding limit, not a slack one; it is simply a limit
 * whose last half is worth very little. Cutting to 750ms costs the same single decision again, so
 * almost all of the value sits below that, and 1,500ms is the conservative point rather than the
 * cheapest one. `LocalSearchBudgetTest` re-measures this whenever these numbers are touched.
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
        // waits for, and that the server thread pays for every Boss decision, is cut.
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

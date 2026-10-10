package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.betterai.search.LocalLookaheadBudgetPolicy
import jbro.cobblemon.mcc.internal.ai.BattleFieldStateView
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalLookaheadPhaseBudgetTest {
    @Test
    fun `the node limit grows as the battle runs out of Pokemon and the clock does not`() {
        val boss = LocalLookaheadBudgetPolicy.forTier(BattleTrainerTier.BOSS)
        fun budget(ally: Int, opponent: Int) = LocalLookaheadBudgetPolicy.forPosition(boss, state(ally, opponent))
        assertEquals(boss, budget(4, 4))
        assertEquals(boss, budget(3, 3))
        assertEquals(boss.nodeLimit * 2, budget(3, 2).nodeLimit)
        assertEquals(boss.nodeLimit * 3, budget(2, 1).nodeLimit)
        assertEquals(boss.timeMillis, budget(1, 1).timeMillis)
    }

    @Test
    fun `the native search sees a third turn on more nodes once two or fewer of its own are left`() {
        val boss = LocalLookaheadBudgetPolicy.forTier(BattleTrainerTier.BOSS)
        fun budget(ally: Int, opponent: Int) = LocalLookaheadBudgetPolicy.forNativePosition(boss, state(ally, opponent))
        assertEquals(10_000, boss.nativeNodeLimit)
        assertEquals(boss, budget(3, 1))
        assertEquals(3, budget(2, 6).nativePlies)
        assertEquals(20_000, budget(2, 6).nativeNodeLimit)
        assertEquals(30_000, budget(1, 6).nativeNodeLimit)
        assertEquals(boss.timeMillis, budget(1, 1).timeMillis)
    }

    private fun state(ally: Int, opponent: Int) = BattleStateView(UUID(0, 1), BattleFormat.DOUBLE, 5, emptyList(),
        BattleFieldStateView.empty(), mapOf(BattleSide.ALLY to ally, BattleSide.OPPONENT to opponent), emptyList(), emptyList())
}

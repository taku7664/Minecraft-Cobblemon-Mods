package jbro.cobblemon.morebattlecontent.internal.tower

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TowerProgressionTest {
    @Test
    fun `wins add one point and losses subtract one without demotion`() {
        var progress = TowerProgress.initial(TowerBattleFormat.SINGLE)

        progress = TowerProgression.record(progress, TowerBattleOutcome.WIN).after
        assertEquals(TowerRank.RANK_1, progress.rank)
        assertEquals(1, progress.rankPoints)

        progress = TowerProgression.record(progress, TowerBattleOutcome.LOSS).after
        assertEquals(TowerRank.RANK_1, progress.rank)
        assertEquals(0, progress.rankPoints)

        progress = TowerProgression.record(progress, TowerBattleOutcome.LOSS).after
        assertEquals(TowerRank.RANK_1, progress.rank)
        assertEquals(0, progress.rankPoints)
    }

    @Test
    fun `boss is the final required win before each tier promotion`() {
        var progress = TowerProgress.initial(TowerBattleFormat.SINGLE)
        val bossWinNumbers = mutableListOf<Int>()

        for (winNumber in 1..33) {
            if (TowerProgression.nextOpponent(progress) == TowerOpponentKind.TIER_BOSS) {
                bossWinNumbers += winNumber
            }
            progress = TowerProgression.record(progress, TowerBattleOutcome.WIN).after
        }

        assertEquals(listOf(6, 15, 27, 33), bossWinNumbers)
        assertEquals(TowerRank.MAX, progress.rank)
        assertEquals(0, progress.rankPoints)
        assertEquals(0, progress.masterCycleWins)
    }

    @Test
    fun `losing a tier boss lowers the gauge and requires earning the point again`() {
        var progress = TowerProgress(
            format = TowerBattleFormat.DOUBLE,
            rank = TowerRank.RANK_3,
            rankPoints = 1,
        )
        assertEquals(TowerOpponentKind.TIER_BOSS, TowerProgression.nextOpponent(progress))

        val loss = TowerProgression.record(progress, TowerBattleOutcome.LOSS)
        progress = loss.after

        assertEquals(TowerOpponentKind.TIER_BOSS, loss.completedOpponent)
        assertFalse(loss.rankAdvanced)
        assertEquals(0, progress.rankPoints)
        assertEquals(TowerOpponentKind.REGULAR, TowerProgression.nextOpponent(progress))

        progress = TowerProgression.record(progress, TowerBattleOutcome.WIN).after
        assertEquals(TowerOpponentKind.TIER_BOSS, TowerProgression.nextOpponent(progress))
    }

    @Test
    fun `max tier uses nine regular wins followed by Leon as the tenth battle`() {
        var progress = TowerProgress(
            format = TowerBattleFormat.SINGLE,
            rank = TowerRank.MAX,
        )

        repeat(9) {
            assertEquals(TowerOpponentKind.REGULAR, TowerProgression.nextOpponent(progress))
            progress = TowerProgression.record(progress, TowerBattleOutcome.WIN).after
        }

        assertEquals(9, progress.masterCycleWins)
        assertEquals(TowerOpponentKind.MASTER_BALL_BOSS, TowerProgression.nextOpponent(progress))

        val bossWin = TowerProgression.record(progress, TowerBattleOutcome.WIN)
        assertEquals(TowerOpponentKind.MASTER_BALL_BOSS, bossWin.completedOpponent)
        assertEquals(0, bossWin.after.masterCycleWins)
        assertFalse(bossWin.rankAdvanced)
    }

    @Test
    fun `max regular loss keeps cycle wins but boss loss resets the cycle`() {
        var progress = TowerProgress(
            format = TowerBattleFormat.DOUBLE,
            rank = TowerRank.MAX,
            masterCycleWins = 8,
        )

        progress = TowerProgression.record(progress, TowerBattleOutcome.LOSS).after
        assertEquals(8, progress.masterCycleWins)

        progress = TowerProgression.record(progress, TowerBattleOutcome.WIN).after
        assertEquals(9, progress.masterCycleWins)
        assertEquals(TowerOpponentKind.MASTER_BALL_BOSS, TowerProgression.nextOpponent(progress))

        val bossLoss = TowerProgression.record(progress, TowerBattleOutcome.LOSS)
        assertEquals(TowerOpponentKind.MASTER_BALL_BOSS, bossLoss.completedOpponent)
        assertEquals(0, bossLoss.after.masterCycleWins)
    }

    @Test
    fun `single and double progress are separate values`() {
        val singles = TowerProgression.record(
            TowerProgress.initial(TowerBattleFormat.SINGLE),
            TowerBattleOutcome.WIN,
        ).after
        val doubles = TowerProgress.initial(TowerBattleFormat.DOUBLE)

        assertEquals(1, singles.rankPoints)
        assertEquals(0, doubles.rankPoints)
        assertTrue(singles.format != doubles.format)
    }
}

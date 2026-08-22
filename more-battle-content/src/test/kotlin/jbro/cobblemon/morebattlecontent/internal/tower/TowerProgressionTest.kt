package jbro.cobblemon.morebattlecontent.internal.tower

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TowerProgressionTest {
    @Test
    fun `wins grow the streak and losses reset only the current streak`() {
        var progress = TowerProgress.initial(TowerBattleFormat.SINGLE)

        repeat(7) { progress = TowerProgression.record(progress, TowerBattleOutcome.WIN).after }
        assertEquals(7, progress.currentWinStreak)
        assertEquals(7, progress.bestWinStreak)

        progress = TowerProgression.record(progress, TowerBattleOutcome.LOSS).after
        assertEquals(0, progress.currentWinStreak)
        assertEquals(7, progress.bestWinStreak)
    }

    @Test
    fun `every fifth battle is a boss and pro bosses reuse the master pool`() {
        val kinds = (0..25).associateWith { streak ->
            TowerProgression.nextOpponent(TowerProgress(TowerBattleFormat.SINGLE, streak, streak))
        }

        assertEquals(TowerOpponentKind.REGULAR, kinds.getValue(3))
        assertEquals(TowerOpponentKind.TIER_BOSS, kinds.getValue(4))
        assertEquals(TowerOpponentKind.TIER_BOSS, kinds.getValue(9))
        assertEquals(TowerOpponentKind.TIER_BOSS, kinds.getValue(14))
        assertEquals(TowerOpponentKind.TIER_BOSS, kinds.getValue(19))
        assertEquals(TowerOpponentKind.MASTER_BALL_BOSS, kinds.getValue(24))
        assertEquals(TowerOpponentKind.REGULAR, kinds.getValue(25))
    }

    @Test
    fun `regular victory bp follows the streak stage`() {
        val expected = mapOf(1 to 1, 4 to 1, 6 to 2, 9 to 2, 11 to 3, 19 to 3, 21 to 4, 49 to 4)

        expected.forEach { (win, bp) ->
            val before = TowerProgress(TowerBattleFormat.SINGLE, win - 1, win - 1)
            assertEquals(bp, TowerProgression.record(before, TowerBattleOutcome.WIN).rewardBp, "win $win")
        }
    }

    @Test
    fun `every boss victory adds five bp to the stage reward`() {
        val expected = mapOf(5 to 6, 10 to 7, 15 to 8, 20 to 8, 25 to 9, 50 to 9)

        expected.forEach { (win, bp) ->
            val before = TowerProgress(TowerBattleFormat.SINGLE, win - 1, win - 1)
            assertEquals(bp, TowerProgression.record(before, TowerBattleOutcome.WIN).rewardBp, "boss win $win")
        }
        assertEquals(
            0,
            TowerProgression.record(TowerProgress(TowerBattleFormat.SINGLE, 4, 4), TowerBattleOutcome.LOSS).rewardBp,
        )
    }

    @Test
    fun `single and double streaks remain independent`() {
        val singles = TowerProgression.record(
            TowerProgress.initial(TowerBattleFormat.SINGLE),
            TowerBattleOutcome.WIN,
        ).after
        val doubles = TowerProgress.initial(TowerBattleFormat.DOUBLE)

        assertEquals(1, singles.currentWinStreak)
        assertEquals(0, doubles.currentWinStreak)
        assertTrue(singles.format != doubles.format)
    }
}

package jbro.cobblemon.mcc.internal.tower

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TowerProgressionTest {
    @Test
    fun `Endless opponents start at level 50 and gain one level every 5 wins up to 100`() {
        fun level(win: Int) = TowerProgression.opponentLevel(TowerMode.ENDLESS, win)
        assertEquals(50, level(1))
        assertEquals(50, level(5))
        assertEquals(51, level(6))
        assertEquals(51, level(10))
        assertEquals(52, level(11))
        assertEquals(59, level(49))
        assertEquals(99, level(250))
        assertEquals(100, level(251))
        assertEquals(100, level(5000))
    }

    @Test
    fun `Normal stays at level 50, brings Champions at the 10th and 20th wins and clears at the 20th`() {
        assertEquals(50, TowerProgression.opponentLevel(TowerMode.NORMAL, 20))
        fun normal(wins: Int) = TowerProgress(TowerBattleFormat.SINGLE, wins, wins, TowerMode.NORMAL)
        assertEquals(listOf(false, true, false, true), listOf(4, 9, 14, 19).map { TowerProgression.nextBossIsChampion(normal(it)) })
        assertTrue(TowerProgression.nextBossIsChampion(TowerProgress(TowerBattleFormat.SINGLE, 4, 4)))
        val clear = TowerProgression.record(normal(19), TowerBattleOutcome.WIN)
        assertTrue(clear.cleared)
        assertEquals(20, clear.after.currentWinStreak)
        assertEquals(false, TowerProgression.record(normal(18), TowerBattleOutcome.WIN).cleared)
        assertEquals(false, TowerProgression.record(TowerProgress(TowerBattleFormat.SINGLE, 19, 19), TowerBattleOutcome.WIN).cleared)
    }

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
        // Endless, the default: the 10th, 20th and 50th wins add its milestone 10 as well.
        val expected = mapOf(5 to 6, 10 to 17, 15 to 8, 20 to 18, 25 to 9, 50 to 19)

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
    fun `clearing Normal pays 30 more and every 10th win in Endless 10 more`() {
        fun reward(mode: TowerMode, win: Int) =
            TowerProgression.record(TowerProgress(TowerBattleFormat.SINGLE, win - 1, win - 1, mode), TowerBattleOutcome.WIN).rewardBp
        assertEquals(7, reward(TowerMode.NORMAL, 10))
        assertEquals(3 + 5 + 30, reward(TowerMode.NORMAL, 20))
        // A full Normal run: 10 + 15 + 40 for the stages and bosses, and the clear.
        assertEquals(95, (1..20).sumOf { reward(TowerMode.NORMAL, it) })
        assertEquals(2 + 5 + 10, reward(TowerMode.ENDLESS, 10))
        assertEquals(4 + 5 + 10, reward(TowerMode.ENDLESS, 30))
        assertEquals(4, reward(TowerMode.ENDLESS, 31))
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

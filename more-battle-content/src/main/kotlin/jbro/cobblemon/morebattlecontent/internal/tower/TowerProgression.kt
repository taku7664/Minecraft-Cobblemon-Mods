package jbro.cobblemon.morebattlecontent.internal.tower

internal enum class TowerBattleOutcome { WIN, LOSS }

internal enum class TowerOpponentKind {
    REGULAR,
    TIER_BOSS,
    MASTER_BALL_BOSS,
}

internal data class TowerProgress(
    val format: TowerBattleFormat,
    val currentWinStreak: Int = 0,
    val bestWinStreak: Int = currentWinStreak,
) {
    init {
        require(currentWinStreak >= 0) { "Current win streak cannot be negative" }
        require(bestWinStreak >= currentWinStreak) { "Best win streak cannot be below current win streak" }
    }

    companion object {
        fun initial(format: TowerBattleFormat) = TowerProgress(format = format)
    }

    val nextStage: TowerStreakStage
        get() = TowerStreakStage.forNextBattle(currentWinStreak)

    val winsIntoSet: Int
        get() = currentWinStreak % BOSS_INTERVAL
}

internal data class TowerProgressUpdate(
    val before: TowerProgress,
    val after: TowerProgress,
    val outcome: TowerBattleOutcome,
    val completedOpponent: TowerOpponentKind,
) {
    val rewardBp: Int
        get() = if (outcome == TowerBattleOutcome.WIN) TowerProgression.rewardForNextVictory(before) else 0
}

internal object TowerProgression {
    fun rewardForNextVictory(progress: TowerProgress): Int =
        progress.nextStage.bpPerWin +
            if (nextOpponent(progress) == TowerOpponentKind.REGULAR) 0 else TOWER_BOSS_BP_BONUS

    fun nextOpponent(progress: TowerProgress): TowerOpponentKind {
        val nextWin = Math.addExact(progress.currentWinStreak, 1)
        if (nextWin % BOSS_INTERVAL != 0) return TowerOpponentKind.REGULAR
        return if (nextWin >= TowerStreakStage.PRO.firstWin) {
            TowerOpponentKind.MASTER_BALL_BOSS
        } else {
            TowerOpponentKind.TIER_BOSS
        }
    }

    fun record(progress: TowerProgress, outcome: TowerBattleOutcome): TowerProgressUpdate {
        val after = when (outcome) {
            TowerBattleOutcome.WIN -> {
                val streak = Math.addExact(progress.currentWinStreak, 1)
                progress.copy(currentWinStreak = streak, bestWinStreak = maxOf(progress.bestWinStreak, streak))
            }
            TowerBattleOutcome.LOSS -> progress.copy(currentWinStreak = 0)
        }
        return TowerProgressUpdate(progress, after, outcome, nextOpponent(progress))
    }
}

internal const val TOWER_BOSS_INTERVAL = 5
internal const val TOWER_BOSS_BP_BONUS = 5
private const val BOSS_INTERVAL = TOWER_BOSS_INTERVAL

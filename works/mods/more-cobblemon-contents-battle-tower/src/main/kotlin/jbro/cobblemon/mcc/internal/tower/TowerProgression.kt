package jbro.cobblemon.mcc.internal.tower

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
    val mode: TowerMode = TowerMode.ENDLESS,
) {
    init {
        require(currentWinStreak >= 0) { "Current win streak cannot be negative" }
        require(bestWinStreak >= currentWinStreak) { "Best win streak cannot be below current win streak" }
    }

    companion object {
        fun initial(format: TowerBattleFormat, mode: TowerMode = TowerMode.ENDLESS) = TowerProgress(format = format, mode = mode)
    }

    val track: TowerTrack
        get() = TowerTrack(format, mode)

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

    /** This win cleared Normal: the run ends, and the next one starts from the first battle. */
    val cleared: Boolean
        get() = outcome == TowerBattleOutcome.WIN && after.mode == TowerMode.NORMAL &&
            after.currentWinStreak >= TOWER_NORMAL_CLEAR_WINS
}

internal object TowerProgression {
    /**
     * The BP for the next win: [bpPerWin], 5 more for a Tower Ace and 10 for a Champion, and a milestone bonus: 10 for
     * clearing Normal at its 20th win, every time, and 10 for every 10th win in Endless.
     */
    fun rewardForNextVictory(progress: TowerProgress): Int {
        val nextWin = Math.addExact(progress.currentWinStreak, 1)
        val boss = when {
            nextOpponent(progress) == TowerOpponentKind.REGULAR -> 0
            nextBossIsChampion(progress) -> TOWER_CHAMPION_BP_BONUS
            else -> TOWER_ACE_BP_BONUS
        }
        val milestone = when (progress.mode) {
            TowerMode.NORMAL -> if (nextWin == TOWER_NORMAL_CLEAR_WINS) TOWER_NORMAL_CLEAR_BP_BONUS else 0
            TowerMode.ENDLESS -> if (nextWin % TOWER_ENDLESS_MILESTONE_WINS == 0) TOWER_ENDLESS_MILESTONE_BP_BONUS else 0
        }
        return bpPerWin(progress.mode, nextWin) + boss + milestone
    }

    /**
     * A win's BP before any bonus: Normal, which can be run again and again, pays 2 for every win; Endless starts at 2
     * and pays one more every 10 wins (11th 3, 21st 4).
     */
    fun bpPerWin(mode: TowerMode, win: Int): Int = when (mode) {
        TowerMode.NORMAL -> TOWER_BASE_BP
        TowerMode.ENDLESS -> TOWER_BASE_BP + (win - 1) / TOWER_ENDLESS_MILESTONE_WINS
    }

    fun nextOpponent(progress: TowerProgress): TowerOpponentKind {
        val nextWin = Math.addExact(progress.currentWinStreak, 1)
        if (nextWin % BOSS_INTERVAL != 0) return TowerOpponentKind.REGULAR
        return if (nextWin >= TowerStreakStage.PRO.firstWin) {
            TowerOpponentKind.MASTER_BALL_BOSS
        } else {
            TowerOpponentKind.TIER_BOSS
        }
    }

    /**
     * The opponents' level for the [win]th win. Normal stays at the Tower's level 50; Endless starts there and adds
     * one every 5 wins (51 from the 6th, 52 from the 11th, 59 at the 49th), up to Cobblemon's level 100. The
     * challenger stays at 50.
     */
    fun opponentLevel(mode: TowerMode, win: Int): Int = when (mode) {
        TowerMode.NORMAL -> TOWER_BATTLE_LEVEL_CAP
        TowerMode.ENDLESS ->
            (TOWER_BATTLE_LEVEL_CAP + (win - 1).coerceAtLeast(0) / TOWER_BOSS_INTERVAL).coerceAtMost(MAX_OPPONENT_LEVEL)
    }

    /**
     * Whether the next battle's boss is a Champion: every boss in Endless; in Normal every 10th win (the 10th and the
     * 20th), with Tower Aces at the 5th and the 15th.
     */
    fun nextBossIsChampion(progress: TowerProgress): Boolean = when (progress.mode) {
        TowerMode.ENDLESS -> true
        TowerMode.NORMAL -> Math.addExact(progress.currentWinStreak, 1) % NORMAL_CHAMPION_INTERVAL == 0
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
private const val MAX_OPPONENT_LEVEL = 100
private const val NORMAL_CHAMPION_INTERVAL = 10
internal const val TOWER_ACE_BP_BONUS = 5
internal const val TOWER_CHAMPION_BP_BONUS = 10
internal const val TOWER_BASE_BP = 2
internal const val TOWER_NORMAL_CLEAR_BP_BONUS = 10
internal const val TOWER_ENDLESS_MILESTONE_WINS = 10
internal const val TOWER_ENDLESS_MILESTONE_BP_BONUS = 10
private const val BOSS_INTERVAL = TOWER_BOSS_INTERVAL

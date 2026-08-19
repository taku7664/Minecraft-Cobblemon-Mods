package jbro.cobblemon.morebattlecontent.internal.tower

internal enum class TowerBattleOutcome { WIN, LOSS }

internal enum class TowerOpponentKind {
    REGULAR,
    TIER_BOSS,
    MASTER_BALL_BOSS,
}

internal data class TowerProgress(
    val format: TowerBattleFormat,
    val rank: TowerRank = TowerRank.RANK_1,
    val rankPoints: Int = 0,
    val masterCycleWins: Int = 0,
) {
    init {
        if (rank == TowerRank.MAX) {
            require(rankPoints == 0) { "MAX rank cannot have rank points" }
            require(masterCycleWins in 0 until MASTER_BALL_CYCLE_SIZE) {
                "Master Ball cycle wins must be between 0 and ${MASTER_BALL_CYCLE_SIZE - 1}"
            }
        } else {
            val required = requireNotNull(rank.winsRequired)
            require(rankPoints in 0 until required) {
                "Rank points must be between 0 and ${required - 1} for ${rank.serializedId}"
            }
            require(masterCycleWins == 0) { "Only MAX rank can have Master Ball cycle wins" }
        }
    }

    companion object {
        fun initial(format: TowerBattleFormat) = TowerProgress(format = format)
    }

    val displayWinsRequired: Int
        get() = rank.winsRequired ?: MASTER_BALL_CYCLE_SIZE
}

internal data class TowerProgressUpdate(
    val before: TowerProgress,
    val after: TowerProgress,
    val outcome: TowerBattleOutcome,
    val completedOpponent: TowerOpponentKind,
) {
    val rankAdvanced: Boolean
        get() = after.rank != before.rank

    val tierAdvanced: Boolean
        get() = after.rank.tier != before.rank.tier
}

internal object TowerProgression {
    fun nextOpponent(progress: TowerProgress): TowerOpponentKind = when {
        progress.rank == TowerRank.MAX && progress.masterCycleWins == MASTER_BALL_REGULAR_WINS ->
            TowerOpponentKind.MASTER_BALL_BOSS

        progress.rank.completionChangesTier &&
            progress.rankPoints == requireNotNull(progress.rank.winsRequired) - 1 ->
            TowerOpponentKind.TIER_BOSS

        else -> TowerOpponentKind.REGULAR
    }

    fun record(progress: TowerProgress, outcome: TowerBattleOutcome): TowerProgressUpdate {
        val opponent = nextOpponent(progress)
        val after = if (progress.rank == TowerRank.MAX) {
            recordMasterBallBattle(progress, outcome, opponent)
        } else {
            recordRankedBattle(progress, outcome)
        }
        return TowerProgressUpdate(progress, after, outcome, opponent)
    }

    private fun recordRankedBattle(
        progress: TowerProgress,
        outcome: TowerBattleOutcome,
    ): TowerProgress {
        if (outcome == TowerBattleOutcome.LOSS) {
            return progress.copy(rankPoints = (progress.rankPoints - 1).coerceAtLeast(0))
        }

        val required = requireNotNull(progress.rank.winsRequired)
        val updatedPoints = progress.rankPoints + 1
        return if (updatedPoints == required) {
            progress.copy(rank = requireNotNull(progress.rank.next), rankPoints = 0)
        } else {
            progress.copy(rankPoints = updatedPoints)
        }
    }

    private fun recordMasterBallBattle(
        progress: TowerProgress,
        outcome: TowerBattleOutcome,
        opponent: TowerOpponentKind,
    ): TowerProgress = when {
        opponent == TowerOpponentKind.MASTER_BALL_BOSS -> progress.copy(masterCycleWins = 0)
        outcome == TowerBattleOutcome.WIN -> progress.copy(masterCycleWins = progress.masterCycleWins + 1)
        else -> progress
    }
}

private const val MASTER_BALL_CYCLE_SIZE = 10
private const val MASTER_BALL_REGULAR_WINS = MASTER_BALL_CYCLE_SIZE - 1

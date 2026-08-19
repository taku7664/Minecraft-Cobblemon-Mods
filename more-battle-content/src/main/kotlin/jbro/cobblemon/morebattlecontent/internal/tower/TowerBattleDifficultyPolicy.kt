package jbro.cobblemon.morebattlecontent.internal.tower

import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfiles
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile

internal object TowerBattleDifficultyPolicy {
    fun resolve(
        rank: TowerRank,
        opponentKind: TowerOpponentKind,
        aiSkill: Int,
    ): BattleTrainerProfile {
        when (opponentKind) {
            TowerOpponentKind.TIER_BOSS,
            TowerOpponentKind.MASTER_BALL_BOSS,
            -> return BattleTrainerProfile.champion(aiSkill)
            TowerOpponentKind.REGULAR -> Unit
        }
        val difficulty = when (rank) {
            TowerRank.RANK_1,
            TowerRank.RANK_2,
            TowerRank.RANK_3,
            -> BattleDifficultyProfiles.INTRODUCTORY

            TowerRank.RANK_4,
            TowerRank.RANK_5,
            TowerRank.RANK_6,
            -> BattleDifficultyProfiles.STANDARD

            TowerRank.RANK_7,
            TowerRank.RANK_8,
            TowerRank.RANK_9,
            TowerRank.RANK_10,
            TowerRank.MAX,
            -> BattleDifficultyProfiles.ADVANCED
        }
        return BattleTrainerProfile.balanced(aiSkill, difficulty)
    }
}

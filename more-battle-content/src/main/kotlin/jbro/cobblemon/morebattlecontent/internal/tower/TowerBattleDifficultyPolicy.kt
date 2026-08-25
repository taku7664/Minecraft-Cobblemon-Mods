package jbro.cobblemon.morebattlecontent.internal.tower

import jbro.cobblemon.morebattlecontent.api.ai.BattleDifficultyProfiles
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile

internal object TowerBattleDifficultyPolicy {
    fun resolve(
        stage: TowerStreakStage,
        opponentKind: TowerOpponentKind,
        aiSkill: Int,
    ): BattleTrainerProfile {
        when (opponentKind) {
            TowerOpponentKind.TIER_BOSS,
            TowerOpponentKind.MASTER_BALL_BOSS,
            -> return BattleTrainerProfile.champion(aiSkill)
            TowerOpponentKind.REGULAR -> Unit
        }
        val difficulty = when (stage) {
            TowerStreakStage.INTRODUCTORY -> BattleDifficultyProfiles.INTRODUCTORY
            TowerStreakStage.PRACTICAL -> BattleDifficultyProfiles.STANDARD
            TowerStreakStage.ADVANCED -> BattleDifficultyProfiles.ADVANCED
            TowerStreakStage.PRO -> BattleDifficultyProfiles.BOSS
        }
        return BattleTrainerProfile.balanced(aiSkill, difficulty)
    }
}

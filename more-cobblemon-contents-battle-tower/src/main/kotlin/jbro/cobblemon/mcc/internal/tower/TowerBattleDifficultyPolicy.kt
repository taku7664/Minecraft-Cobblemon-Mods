package jbro.cobblemon.mcc.internal.tower

import jbro.cobblemon.mcc.internal.ai.BattleDifficultyProfiles
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile

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
            // Regular battles stop at ADVANCED; the BOSS search is for the bosses alone, so a long streak does not
            // run the heaviest search every battle.
            TowerStreakStage.PRO -> BattleDifficultyProfiles.ADVANCED
        }
        return BattleTrainerProfile.balanced(aiSkill, difficulty)
    }
}

package jbro.cobblemon.mcc.internal.tower

import jbro.cobblemon.mcc.internal.ai.BattleDifficultyProfiles
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile

internal object TowerBattleDifficultyPolicy {
    fun resolve(
        stage: TowerStreakStage,
        opponentKind: TowerOpponentKind,
        aiSkill: Int,
        mode: TowerMode = TowerMode.ENDLESS,
    ): BattleTrainerProfile {
        when (opponentKind) {
            TowerOpponentKind.TIER_BOSS,
            TowerOpponentKind.MASTER_BALL_BOSS,
            -> return boss(stage, aiSkill, mode)
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

    /**
     * Endless is for challengers who cleared Normal, so every boss plays at BOSS. Normal climbs one step above its
     * stage instead of jumping from the introductory regulars straight to BOSS: the 5th-win Tower Ace at STANDARD,
     * the 10th-win Champion at ADVANCED, and the 15th and 20th at BOSS.
     */
    private fun boss(stage: TowerStreakStage, aiSkill: Int, mode: TowerMode): BattleTrainerProfile {
        val champion = BattleTrainerProfile.champion(aiSkill)
        if (mode == TowerMode.ENDLESS) return champion
        return when (stage) {
            TowerStreakStage.INTRODUCTORY -> champion.copy(difficulty = BattleDifficultyProfiles.STANDARD)
            TowerStreakStage.PRACTICAL -> champion.copy(difficulty = BattleDifficultyProfiles.ADVANCED)
            TowerStreakStage.ADVANCED, TowerStreakStage.PRO -> champion
        }
    }
}

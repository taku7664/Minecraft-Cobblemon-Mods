package jbro.cobblemon.mcc.internal.tower

import jbro.cobblemon.mcc.internal.ai.BattleDifficultyProfiles
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile

internal object TowerBattleDifficultyPolicy {
    fun resolve(
        stage: TowerStreakStage,
        opponentKind: TowerOpponentKind,
        aiSkill: Int,
        mode: TowerMode = TowerMode.ENDLESS,
        /** The boss is a Champion rather than a Tower Ace. */
        champion: Boolean = true,
    ): BattleTrainerProfile {
        when (opponentKind) {
            TowerOpponentKind.TIER_BOSS,
            TowerOpponentKind.MASTER_BALL_BOSS,
            -> return boss(stage, aiSkill, mode, champion)
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
     * BOSS is the Champions' alone. Endless, for challengers who cleared Normal, brings a Champion at BOSS to every
     * boss battle. In Normal the Tower Aces, strong trainers rather than Champions, play ADVANCED at the 5th and
     * 15th wins, and its Champions ADVANCED at the 10th win and BOSS at the 20th.
     */
    private fun boss(stage: TowerStreakStage, aiSkill: Int, mode: TowerMode, champion: Boolean): BattleTrainerProfile {
        if (!champion) return BattleTrainerProfile.balanced(aiSkill, BattleDifficultyProfiles.ADVANCED)
        val profile = BattleTrainerProfile.champion(aiSkill)
        if (mode == TowerMode.ENDLESS) return profile
        return when (stage) {
            TowerStreakStage.INTRODUCTORY, TowerStreakStage.PRACTICAL -> profile.copy(difficulty = BattleDifficultyProfiles.ADVANCED)
            TowerStreakStage.ADVANCED, TowerStreakStage.PRO -> profile
        }
    }
}

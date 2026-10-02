package jbro.cobblemon.mcc.internal.factory

import jbro.cobblemon.mcc.internal.ai.BattleDifficultyProfiles
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier

/**
 * The Factory Head (singles battles 21 and 49) plays at BOSS; every other battle plays at its trainer's skill but no
 * higher than ADVANCED, so the heaviest search runs only for the Head.
 */
internal object FactoryBattleDifficultyPolicy {
    fun resolve(
        battleNumber: Int,
        format: FactoryBattleFormat,
        aiSkill: Int,
    ): BattleTrainerProfile = if (FactoryProgression.isFactoryHeadBattle(battleNumber, format)) {
        BattleTrainerProfile.boss(aiSkill)
    } else {
        val difficulty = BattleDifficultyProfiles.forSkillLevel(aiSkill)
        BattleTrainerProfile.balanced(aiSkill, if (difficulty.tier == BattleTrainerTier.BOSS) BattleDifficultyProfiles.ADVANCED else difficulty)
    }
}

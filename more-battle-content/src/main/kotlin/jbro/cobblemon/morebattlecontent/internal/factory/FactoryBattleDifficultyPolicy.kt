package jbro.cobblemon.morebattlecontent.internal.factory

import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile

internal object FactoryBattleDifficultyPolicy {
    fun resolve(
        battleNumber: Int,
        format: FactoryBattleFormat,
        aiSkill: Int,
    ): BattleTrainerProfile = if (FactoryProgression.isFactoryHeadBattle(battleNumber, format)) {
        BattleTrainerProfile.boss(aiSkill)
    } else {
        BattleTrainerProfile.balanced(aiSkill)
    }
}

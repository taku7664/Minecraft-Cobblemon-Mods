package jbro.cobblemon.mcc.internal.factory

import jbro.cobblemon.mcc.internal.ai.BattleDifficultyProfiles
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile

/**
 * The AI climbs with the run, not with whichever trainer is drawn: STANDARD in rounds 1 and 2 (battles 1 to 14),
 * ADVANCED from round 3, and BOSS for the Factory Head (singles battles 21 and 49) alone. The trainer's own skill
 * still drives Cobblemon's fallback AI.
 */
internal object FactoryBattleDifficultyPolicy {
    fun resolve(
        battleNumber: Int,
        format: FactoryBattleFormat,
        aiSkill: Int,
    ): BattleTrainerProfile = if (FactoryProgression.isFactoryHeadBattle(battleNumber, format)) {
        BattleTrainerProfile.boss(aiSkill)
    } else {
        val round = FactoryProgression.roundForBattle(battleNumber)
        BattleTrainerProfile.balanced(aiSkill, if (round <= STANDARD_ROUNDS) BattleDifficultyProfiles.STANDARD else BattleDifficultyProfiles.ADVANCED)
    }

    private const val STANDARD_ROUNDS = 2
}

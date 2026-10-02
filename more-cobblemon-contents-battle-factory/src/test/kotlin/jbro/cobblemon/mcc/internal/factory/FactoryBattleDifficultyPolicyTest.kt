package jbro.cobblemon.mcc.internal.factory

import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FactoryBattleDifficultyPolicyTest {
    @Test
    fun `only the Factory Head plays at BOSS, a skill 5 trainer plays ADVANCED elsewhere`() {
        assertEquals(BattleTrainerTier.BOSS, FactoryBattleDifficultyPolicy.resolve(21, FactoryBattleFormat.SINGLE, 2).difficulty.tier)
        assertEquals(BattleTrainerTier.BOSS, FactoryBattleDifficultyPolicy.resolve(49, FactoryBattleFormat.SINGLE, 5).difficulty.tier)
        assertEquals(BattleTrainerTier.ADVANCED, FactoryBattleDifficultyPolicy.resolve(20, FactoryBattleFormat.SINGLE, 5).difficulty.tier)
        assertEquals(BattleTrainerTier.ADVANCED, FactoryBattleDifficultyPolicy.resolve(21, FactoryBattleFormat.DOUBLE, 5).difficulty.tier)
        assertEquals(BattleTrainerTier.ADVANCED, FactoryBattleDifficultyPolicy.resolve(3, FactoryBattleFormat.SINGLE, 4).difficulty.tier)
        assertEquals(BattleTrainerTier.STANDARD, FactoryBattleDifficultyPolicy.resolve(3, FactoryBattleFormat.SINGLE, 2).difficulty.tier)
        // The trainer's own skill still drives Cobblemon's fallback AI.
        assertEquals(5, FactoryBattleDifficultyPolicy.resolve(20, FactoryBattleFormat.SINGLE, 5).skillLevel)
    }
}

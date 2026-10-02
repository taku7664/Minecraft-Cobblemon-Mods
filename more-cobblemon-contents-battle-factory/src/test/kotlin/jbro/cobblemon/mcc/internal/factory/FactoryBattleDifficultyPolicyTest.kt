package jbro.cobblemon.mcc.internal.factory

import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FactoryBattleDifficultyPolicyTest {
    @Test
    fun `the AI climbs with the round and only the Factory Head plays at BOSS`() {
        assertEquals(BattleTrainerTier.BOSS, FactoryBattleDifficultyPolicy.resolve(21, FactoryBattleFormat.SINGLE, 2).difficulty.tier)
        assertEquals(BattleTrainerTier.BOSS, FactoryBattleDifficultyPolicy.resolve(49, FactoryBattleFormat.SINGLE, 5).difficulty.tier)
        assertEquals(BattleTrainerTier.ADVANCED, FactoryBattleDifficultyPolicy.resolve(20, FactoryBattleFormat.SINGLE, 5).difficulty.tier)
        assertEquals(BattleTrainerTier.ADVANCED, FactoryBattleDifficultyPolicy.resolve(15, FactoryBattleFormat.SINGLE, 2).difficulty.tier)
        // Rounds 1 and 2 play STANDARD whatever trainer is drawn.
        assertEquals(BattleTrainerTier.STANDARD, FactoryBattleDifficultyPolicy.resolve(3, FactoryBattleFormat.SINGLE, 5).difficulty.tier)
        assertEquals(BattleTrainerTier.STANDARD, FactoryBattleDifficultyPolicy.resolve(14, FactoryBattleFormat.SINGLE, 4).difficulty.tier)
        // The trainer's own skill still drives Cobblemon's fallback AI.
        assertEquals(5, FactoryBattleDifficultyPolicy.resolve(20, FactoryBattleFormat.SINGLE, 5).skillLevel)
    }
}

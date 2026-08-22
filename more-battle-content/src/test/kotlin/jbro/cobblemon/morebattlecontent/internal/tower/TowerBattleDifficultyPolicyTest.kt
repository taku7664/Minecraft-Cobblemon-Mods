package jbro.cobblemon.morebattlecontent.internal.tower

import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TowerBattleDifficultyPolicyTest {
    @Test
    fun `regular opponents rise from introductory through advanced local ai profiles`() {
        val expected = mapOf(
            TowerStreakStage.INTRODUCTORY to BattleTrainerTier.INTRODUCTORY,
            TowerStreakStage.PRACTICAL to BattleTrainerTier.STANDARD,
            TowerStreakStage.ADVANCED to BattleTrainerTier.ADVANCED,
            TowerStreakStage.PRO to BattleTrainerTier.ADVANCED,
        )
        expected.forEach { (stage, tier) ->
            assertEquals(tier, TowerBattleDifficultyPolicy.resolve(stage, TowerOpponentKind.REGULAR, 1).difficulty.tier)
        }
    }

    @Test
    fun `every fifth opponent uses boss difficulty`() {
        TowerStreakStage.entries.forEach { stage ->
            listOf(TowerOpponentKind.TIER_BOSS, TowerOpponentKind.MASTER_BALL_BOSS).forEach { kind ->
                assertEquals(BattleTrainerTier.BOSS, TowerBattleDifficultyPolicy.resolve(stage, kind, 4).difficulty.tier)
            }
        }
    }
}

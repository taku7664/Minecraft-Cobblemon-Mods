package jbro.cobblemon.mcc.internal.tower

import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TowerBattleDifficultyPolicyTest {
    @Test
    fun `regular opponents use the approved one one two two lookahead ladder`() {
        val expected = mapOf(
            TowerStreakStage.INTRODUCTORY to (BattleTrainerTier.INTRODUCTORY to 1),
            TowerStreakStage.PRACTICAL to (BattleTrainerTier.STANDARD to 1),
            TowerStreakStage.ADVANCED to (BattleTrainerTier.ADVANCED to 2),
            TowerStreakStage.PRO to (BattleTrainerTier.ADVANCED to 2),
        )
        expected.forEach { (stage, expectation) ->
            val difficulty = TowerBattleDifficultyPolicy.resolve(stage, TowerOpponentKind.REGULAR, 1).difficulty
            assertEquals(expectation.first, difficulty.tier)
            assertEquals(expectation.second, difficulty.lookaheadPlies)
        }
    }

    @Test
    fun `Normal bosses climb one step above their stage up to boss difficulty`() {
        val expected = mapOf(
            TowerStreakStage.INTRODUCTORY to BattleTrainerTier.STANDARD,
            TowerStreakStage.PRACTICAL to BattleTrainerTier.ADVANCED,
            TowerStreakStage.ADVANCED to BattleTrainerTier.BOSS,
        )
        expected.forEach { (stage, tier) ->
            assertEquals(tier, TowerBattleDifficultyPolicy.resolve(stage, TowerOpponentKind.TIER_BOSS, 4, TowerMode.NORMAL).difficulty.tier)
        }
        assertEquals(BattleTrainerTier.INTRODUCTORY,
            TowerBattleDifficultyPolicy.resolve(TowerStreakStage.INTRODUCTORY, TowerOpponentKind.REGULAR, 1, TowerMode.NORMAL).difficulty.tier)
    }

    @Test
    fun `every fifth Endless opponent uses boss difficulty`() {
        TowerStreakStage.entries.forEach { stage ->
            listOf(TowerOpponentKind.TIER_BOSS, TowerOpponentKind.MASTER_BALL_BOSS).forEach { kind ->
                assertEquals(BattleTrainerTier.BOSS, TowerBattleDifficultyPolicy.resolve(stage, kind, 4).difficulty.tier)
            }
        }
    }
}

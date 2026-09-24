package jbro.cobblemon.morebattlecontent.internal.tower

import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TowerBattleDifficultyPolicyTest {
    @Test
    fun `regular opponents use the approved one two two three lookahead ladder`() {
        val expected = mapOf(
            TowerStreakStage.INTRODUCTORY to (BattleTrainerTier.INTRODUCTORY to 1),
            TowerStreakStage.PRACTICAL to (BattleTrainerTier.STANDARD to 2),
            TowerStreakStage.ADVANCED to (BattleTrainerTier.ADVANCED to 2),
            TowerStreakStage.PRO to (BattleTrainerTier.BOSS to 3),
        )
        expected.forEach { (stage, expectation) ->
            val difficulty = TowerBattleDifficultyPolicy.resolve(stage, TowerOpponentKind.REGULAR, 1).difficulty
            assertEquals(expectation.first, difficulty.tier)
            assertEquals(expectation.second, difficulty.lookaheadPlies)
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

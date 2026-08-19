package jbro.cobblemon.morebattlecontent.internal.tower

import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TowerBattleDifficultyPolicyTest {
    @Test
    fun `regular tower ranks use introductory standard then advanced difficulty`() {
        val expected = mapOf(
            TowerRank.RANK_1 to BattleTrainerTier.INTRODUCTORY,
            TowerRank.RANK_2 to BattleTrainerTier.INTRODUCTORY,
            TowerRank.RANK_3 to BattleTrainerTier.INTRODUCTORY,
            TowerRank.RANK_4 to BattleTrainerTier.STANDARD,
            TowerRank.RANK_5 to BattleTrainerTier.STANDARD,
            TowerRank.RANK_6 to BattleTrainerTier.STANDARD,
            TowerRank.RANK_7 to BattleTrainerTier.ADVANCED,
            TowerRank.RANK_8 to BattleTrainerTier.ADVANCED,
            TowerRank.RANK_9 to BattleTrainerTier.ADVANCED,
            TowerRank.RANK_10 to BattleTrainerTier.ADVANCED,
            TowerRank.MAX to BattleTrainerTier.ADVANCED,
        )

        expected.forEach { (rank, tier) ->
            assertEquals(
                tier,
                TowerBattleDifficultyPolicy.resolve(rank, TowerOpponentKind.REGULAR, aiSkill = 1).difficulty.tier,
                rank.name,
            )
        }
    }

    @Test
    fun `every promotion and master champion is boss difficulty with champion personality`() {
        TowerRank.entries.forEach { rank ->
            listOf(TowerOpponentKind.TIER_BOSS, TowerOpponentKind.MASTER_BALL_BOSS).forEach { kind ->
                val profile = TowerBattleDifficultyPolicy.resolve(rank, kind, aiSkill = 4)

                assertEquals(BattleTrainerTier.BOSS, profile.difficulty.tier, "$rank $kind")
                assertEquals(0.7, profile.personality.aggression, "$rank $kind")
                assertEquals(0.8, profile.personality.information, "$rank $kind")
            }
        }
    }
}

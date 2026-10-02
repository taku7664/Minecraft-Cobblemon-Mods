package jbro.cobblemon.mcc.betterai

import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.betterai.policy.LocalActionMixingContext
import jbro.cobblemon.mcc.betterai.policy.LocalHighestRankedActionSelector
import jbro.cobblemon.mcc.betterai.search.NativeProductRankAdapter
import jbro.cobblemon.mcc.betterai.search.NativeRootActionValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LocalHighestRankedActionSelectorTest {
    @Test
    fun `score leader wins for every seed and risk setting`() {
        val ranked = ranks(144.858, 124.548, 44.904)
        for (risk in listOf(0.0, 0.5, 1.0)) repeat(128) { seed ->
            val selection = LocalHighestRankedActionSelector.choose(
                ranked, seed.toLong(), LocalActionMixingContext.balanced(risk),
            )
            assertEquals(ranked.first(), selection.rank)
            assertEquals(seed.toLong(), selection.seed)
            assertEquals(1, selection.shortlistSize)
            assertEquals(1.0, selection.probability)
            assertEquals(mapOf("move:0" to 1.0), selection.probabilitiesByActionId)
            assertEquals(mapOf("move:1" to "lower_rank", "move:2" to "lower_rank"), selection.exclusionsByActionId)
        }
    }

    @Test
    fun `equal and negative scores preserve the first ranked action`() {
        for (ranked in listOf(ranks(201.390, 201.390, 200.990), ranks(-15.072, -16.201, -38.250))) {
            repeat(128) { seed ->
                assertEquals(ranked.first(), LocalHighestRankedActionSelector.choose(
                    ranked, seed.toLong(), LocalActionMixingContext.balanced(0.5),
                ).rank)
            }
        }
    }

    @Test
    fun `empty ranking is rejected explicitly`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalHighestRankedActionSelector.choose(emptyList(), 0L, LocalActionMixingContext.balanced(0.5))
        }
    }

    private fun ranks(vararg scores: Double) = NativeProductRankAdapter.rank(scores.mapIndexed { index, score ->
        NativeRootActionValue(BattleActionCandidate(
            actionId = "move:$index", kind = BattleActionKind.USE_MOVE,
            actorSlot = 0, moveSlot = index, moveId = "tackle",
        ), score)
    })
}

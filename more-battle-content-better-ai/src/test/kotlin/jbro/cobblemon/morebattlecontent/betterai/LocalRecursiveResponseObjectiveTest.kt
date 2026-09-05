package jbro.cobblemon.morebattlecontent.betterai

import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.search.LocalOpponentResponseModel
import jbro.cobblemon.morebattlecontent.betterai.search.LocalOpponentResponseValue
import jbro.cobblemon.morebattlecontent.betterai.search.LocalResponseValue
import jbro.cobblemon.morebattlecontent.betterai.search.LocalSearchResponseObjective
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.exp

class LocalRecursiveResponseObjectiveTest {
    @Test
    fun `learned categories blend with robust value and retain lowest hp`() {
        val move = BattleActionCandidate("move", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0, moveId = "a")
        val switch = BattleActionCandidate("switch", BattleActionKind.SWITCH, actorSlot = 0,
            switchPokemonId = java.util.UUID(0, 1))
        val values = listOf(LocalOpponentResponseValue(move, LocalResponseValue(-1.0, 0.2, 0.7)),
            LocalOpponentResponseValue(switch, LocalResponseValue(1.0, 1.0, 0.1)))
        val profile = BattleTrainerProfile.balanced().copy(difficulty = BattleDifficultyProfiles.BOSS)
        val memory = BattleTacticalMemoryView(tendencies = listOf(
            BattleTendencyView(BattleSituation.GENERAL, BattlePredictedResponse.MOVE, 8, 1.6, 0.2),
            BattleTendencyView(BattleSituation.GENERAL, BattlePredictedResponse.SWITCH, 8, 6.4, 0.8)))
        val situations = setOf(BattleSituation.GENERAL)
        val prior = requireNotNull(LocalOpponentResponseModel.distribution(listOf(move, switch), memory,
            profile.personality.information, situations))
        val robust = LocalSearchResponseObjective.robust(values.map { it.value }, profile.difficulty.tier)
        val result = requireNotNull(LocalSearchResponseObjective.aggregate(values, memory, profile, situations))
        val modeledValue = -prior.weights.getValue(move) + prior.weights.getValue(switch)
        assertEquals(robust.value * (1 - prior.influence) + modeledValue * prior.influence, result.value, 1e-15)
        assertTrue(result.value > robust.value)
        assertEquals(0.1, result.ownRemainingHpFraction)
        assertEquals(robust, LocalSearchResponseObjective.aggregate(values, BattleTacticalMemoryView(), profile, situations))
        assertNull(LocalSearchResponseObjective.aggregate(emptyList(), memory, profile, situations))
    }

    @Test
    fun `equal value tie retains existing first response execution semantics`() {
        val first = LocalResponseValue(0.0, 0.2, 0.8)
        val second = LocalResponseValue(0.0, 1.0, 0.1)
        val result = LocalSearchResponseObjective.robust(listOf(first, second), BattleTrainerTier.BOSS)
        assertEquals(0.6 * 0.15 + 0.2 * 0.85, result.ownExecutionProbability, 1e-15)
        assertEquals(0.1, result.ownRemainingHpFraction)
    }

    @Test
    fun `single response preserves all diagnostics`() {
        val response = LocalResponseValue(-2.0, 0.25, 0.1)
        for (tier in BattleTrainerTier.entries) {
            assertEquals(response, LocalSearchResponseObjective.robust(listOf(response), tier))
        }
    }

    @Test
    fun `tier weights preserve existing soft minimum not uniform mean`() {
        val responses = listOf(LocalResponseValue(-1.0, 0.2, 0.8), LocalResponseValue(0.0, 1.0, 0.1))
        val softWeight = exp(-1.0 / 0.35)
        val expectedValue = -1.0 / (1.0 + softWeight)
        val expectedExecution = (0.2 + softWeight) / (1.0 + softWeight)
        val tiers = listOf(BattleTrainerTier.INTRODUCTORY to 0.20, BattleTrainerTier.STANDARD to 0.40,
            BattleTrainerTier.ADVANCED to 0.65, BattleTrainerTier.BOSS to 0.85)
        for ((tier, pessimism) in tiers) {
            val result = LocalSearchResponseObjective.robust(responses, tier)
            assertEquals(expectedValue * (1 - pessimism) - pessimism, result.value, 1e-15)
            assertEquals(expectedExecution * (1 - pessimism) + 0.2 * pessimism,
                result.ownExecutionProbability, 1e-15)
            assertEquals(0.1, result.ownRemainingHpFraction)
            assertTrue(result.value < -0.5)
        }
    }

    @Test
    fun `extreme response gap remains finite and does not hide lowest hp`() {
        val result = LocalSearchResponseObjective.robust(listOf(
            LocalResponseValue(-1000.0, 0.0, 0.9), LocalResponseValue(1000.0, 1.0, 0.0)), BattleTrainerTier.BOSS)
        assertEquals(-1000.0, result.value)
        assertEquals(0.0, result.ownExecutionProbability)
        assertEquals(0.0, result.ownRemainingHpFraction)
    }

    @Test
    fun `empty response list cannot create a score`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalSearchResponseObjective.robust(emptyList(), BattleTrainerTier.BOSS)
        }
    }
}

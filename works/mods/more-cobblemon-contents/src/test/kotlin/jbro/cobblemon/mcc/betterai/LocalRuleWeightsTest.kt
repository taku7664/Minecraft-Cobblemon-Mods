package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.betterai.matchup.LocalRuleWeights
import jbro.cobblemon.mcc.internal.ai.BattleFieldStateView
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalRuleWeightsTest {
    @Test
    fun `the ace weighs most and the weights keep a mean of one inside the band`() {
        val weights = LocalRuleWeights.weights(state(), BattleTrainerTier.BOSS, mapOf(ACE to 0.9, SUPPORT to 0.2, BENCH to 0.4), null)
        val band = requireNotNull(LocalRuleWeights.allyBand(BattleTrainerTier.BOSS))
        assertTrue(weights.getValue(ACE) > weights.getValue(BENCH))
        assertTrue(weights.getValue(BENCH) > weights.getValue(SUPPORT))
        assertTrue(weights.values.all { it in band.minimum..band.maximum }, "$weights")
        assertEquals(1.0, listOf(ACE, SUPPORT, BENCH).map(weights::getValue).average(), 0.05)
        assertTrue(FOE !in weights)
    }

    @Test
    fun `standard and introductory trainers get no rule weights`() {
        val aces = mapOf(ACE to 0.9, SUPPORT to 0.2, BENCH to 0.4)
        assertEquals(emptyMap<UUID, Double>(), LocalRuleWeights.weights(state(), BattleTrainerTier.STANDARD, aces, null))
        assertEquals(emptyMap<UUID, Double>(), LocalRuleWeights.weights(state(), BattleTrainerTier.INTRODUCTORY, aces, null))
    }

    private fun state() = BattleStateView(
        battleId = UUID.fromString("00000000-0000-0000-0000-000000004400"), format = BattleFormat.SINGLE, turn = 3,
        pokemon = listOf(mon(ACE, BattleSide.ALLY, 0), mon(SUPPORT, BattleSide.ALLY, null), mon(BENCH, BattleSide.ALLY, null),
            mon(FOE, BattleSide.OPPONENT, 0)),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 3, BattleSide.OPPONENT to 1),
        observedEvents = emptyList(), inferences = emptyList(),
    )

    private fun mon(id: UUID, side: BattleSide, slot: Int?) = BattlePokemonStateView(
        battlePokemonId = id, side = side, activeSlot = slot, speciesId = "cobblemon:eevee", formId = null, level = 50,
        hpFraction = 1.0, statusId = null, statStages = emptyMap(), knownMoveIds = emptySet(), knownAbilityId = null,
        knownHeldItemId = null, fainted = false, knownTypeIds = setOf("normal"),
    )

    private companion object {
        val ACE: UUID = UUID.fromString("00000000-0000-0000-0000-000000004401")
        val SUPPORT: UUID = UUID.fromString("00000000-0000-0000-0000-000000004402")
        val BENCH: UUID = UUID.fromString("00000000-0000-0000-0000-000000004403")
        val FOE: UUID = UUID.fromString("00000000-0000-0000-0000-000000004404")
    }
}

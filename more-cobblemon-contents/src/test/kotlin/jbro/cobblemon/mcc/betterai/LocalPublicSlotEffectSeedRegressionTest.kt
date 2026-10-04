package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.mcc.betterai.state.RecursiveHistoryProjector
import jbro.cobblemon.mcc.betterai.state.RecursiveSnapshotActionConstraints
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalPublicSlotEffectSeedRegressionTest {
    @Test
    fun `observed Wish uses the wishers HP after a different recipient switches into its slot`() {
        val source = mon(BattleSide.ALLY, null, maxHp = 201)
        val current = mon(BattleSide.ALLY, 0, maxHp = 200, hp = .1)
        val incoming = mon(BattleSide.ALLY, null, maxHp = 400, hp = .1)
        val foe = mon(BattleSide.OPPONENT, 0)
        val before = state(listOf(source, current, incoming, foe), BattleSide.ALLY,
            BattleTimedEffectView("wishslot0", 1, sourcePokemonId = source.battlePokemonId,
                targetSlot = 0, sourceMoveId = "wish"))
        val history = RecursiveSnapshotActionConstraints.seed(before)
        val switched = before.copyState(pokemon = before.pokemon.map {
            when (it.battlePokemonId) {
                current.battlePokemonId -> it.copyState(activeSlot = null)
                incoming.battlePokemonId -> it.copyState(activeSlot = 0)
                else -> it
            }
        })
        val outcomes = turn(switched, history)
        assertEquals(201.0, history.wishSourceMaxHpBySlot[BattleSide.ALLY to 0])
        outcomes.forEach {
            assertEquals(.35, it.state.pokemon.single { mon -> mon.battlePokemonId == incoming.battlePokemonId }.hpFraction, 1e-9)
        }
    }

    @Test
    fun `observed Future Sight impacts its target slot even after its source has switched out`() {
        val source = mon(BattleSide.ALLY, null)
        val target = mon(BattleSide.OPPONENT, 1)
        val other = mon(BattleSide.OPPONENT, 0)
        val before = state(listOf(source, mon(BattleSide.ALLY, 0), other, target), BattleSide.OPPONENT,
            BattleTimedEffectView("futuresightslot1", 1, sourcePokemonId = source.battlePokemonId,
                targetSlot = 1, sourceMoveId = "futuresight"))
        val history = RecursiveSnapshotActionConstraints.seed(before)
        assertEquals(120.0, history.delayedStrikes.single().moveDetails.power)
        assertEquals(1, history.delayedStrikes.single().targetSlot)
        val outcomes = turn(before, history)
        assertTrue(outcomes.isNotEmpty())
        outcomes.forEach {
            assertTrue(it.state.pokemon.single { mon -> mon.battlePokemonId == target.battlePokemonId }.hpFraction < 1.0)
            assertEquals(1.0, it.state.pokemon.single { mon -> mon.battlePokemonId == other.battlePokemonId }.hpFraction)
            assertEquals(null, it.state.pokemon.single { mon -> mon.battlePokemonId == source.battlePokemonId }.activeSlot)
        }
    }

    @Test
    fun `observed Doom Desire waits for its remaining turn then strikes`() {
        val source = mon(BattleSide.ALLY, null)
        val target = mon(BattleSide.OPPONENT, 0)
        val before = state(listOf(source, mon(BattleSide.ALLY, 0), target), BattleSide.OPPONENT,
            BattleTimedEffectView("doomdesireslot0", 2, sourcePokemonId = source.battlePokemonId,
                targetSlot = 0, sourceMoveId = "doomdesire"))
        val history = RecursiveSnapshotActionConstraints.seed(before)
        assertEquals(140.0, history.delayedStrikes.single().moveDetails.power)
        val first = turn(before, history).single()
        assertEquals(1.0, first.state.pokemon.single { it.battlePokemonId == target.battlePokemonId }.hpFraction)
        val next = RecursiveHistoryProjector.project(history, before, first, WAIT, WAIT)
        assertEquals(1, next.delayedStrikes.single().remainingTurns)
        turn(first.state, next).forEach {
            assertTrue(it.state.pokemon.single { mon -> mon.battlePokemonId == target.battlePokemonId }.hpFraction < 1.0)
        }
    }

    private fun turn(state: BattleStateView, history: jbro.cobblemon.mcc.betterai.state.RecursiveActionHistory) =
        PublicSingleTurnProjector.project(state, WAIT, WAIT,
            BattleDecisionContext(UUID.randomUUID(), state, listOf(WAIT), Long.MAX_VALUE,
                BattleTacticalMemoryView.empty(), publicActionCatalog = BattlePublicActionCatalogView(emptyList())), history)

    private fun state(pokemon: List<BattlePokemonStateView>, effectSide: BattleSide, effect: BattleTimedEffectView) =
        BattleStateView(UUID.randomUUID(), if (pokemon.any { it.activeSlot == 1 }) BattleFormat.DOUBLE else BattleFormat.SINGLE,
            5, pokemon, BattleFieldStateView(null, null, emptyList(), emptyList(),
                BattleSide.entries.associateWith { if (it == effectSide) listOf(effect) else emptyList() }),
            BattleSide.entries.associateWith { side -> pokemon.count { it.side == side && !it.fainted } }, emptyList(), emptyList())

    private fun mon(side: BattleSide, slot: Int?, maxHp: Int = 400, hp: Double = 1.0) = BattlePokemonStateView(
        UUID.randomUUID(), side, slot, "showdown:probe", null, 100, hp, null, emptyMap(), emptySet(), null, null,
        false, setOf("normal"), BattleCombatStatRangesView(BattleIntegerRange(maxHp, maxHp), BattleIntegerRange(100, 100),
            BattleIntegerRange(100, 100), BattleIntegerRange(100, 100), BattleIntegerRange(100, 100),
            BattleIntegerRange(100, 100), if (side == BattleSide.ALLY) BattleCombatStatKnowledge.EXACT_OWN
            else BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE))

    companion object { private val WAIT = BattleActionCandidate("wait", BattleActionKind.WAIT) }
}

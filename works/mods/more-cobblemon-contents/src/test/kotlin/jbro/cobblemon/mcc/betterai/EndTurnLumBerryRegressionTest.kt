package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.betterai.state.LocalEndTurnStateProjector
import jbro.cobblemon.mcc.betterai.outcome.PublicSingleTurnProjector
import jbro.cobblemon.mcc.betterai.state.RecursiveActionHistory
import jbro.cobblemon.mcc.betterai.state.RecursiveHistoryProjector
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** G-212: Lum's AfterSetStatus and Update events after public residual state changes. */
class EndTurnLumBerryRegressionTest {
    @Test
    fun `Lum Berry cures the sleep Yawn just inflicted and is consumed`() {
        val holder = mon(BattleSide.ALLY, 0, item = "lumberry")
        val after = yawn(holder, mon(BattleSide.OPPONENT, 0))
        assertNull(after.statusId)
        assertEquals("", after.knownHeldItemId)
        assertEquals(1.0, after.hpFraction, 1e-9)
    }

    @Test
    fun `Magic Room expiring allows Update to cure poison without refunding its earlier damage`() {
        val holder = mon(BattleSide.ALLY, 0, item = "lumberry", status = "psn")
        val after = LocalEndTurnStateProjector.project(state(holder, mon(BattleSide.OPPONENT, 0), magicRoomTurns = 1))
        val cured = pokemon(after, holder)
        assertNull(cured.statusId)
        assertEquals("", cured.knownHeldItemId)
        assertEquals(0.875, cured.hpFraction, 1e-9)
        assertTrue(after.field.roomEffects.none { it.effectId == "magicroom" })
    }

    @Test
    fun `Magic Room expiring after Yawn lets Update eat Lum`() {
        val holder = mon(BattleSide.ALLY, 0, item = "lumberry")
        val after = yawn(holder, mon(BattleSide.OPPONENT, 0), magicRoomTurns = 1)
        assertNull(after.statusId)
        assertEquals("", after.knownHeldItemId)
    }

    @Test
    fun `Unnerve dying to residual damage lets Update cure the holders poison`() {
        val holder = mon(BattleSide.ALLY, 0, item = "lumberry", status = "psn")
        val unnerve = mon(BattleSide.OPPONENT, 0, ability = "unnerve", status = "psn", hp = 0.0625)
        // A surviving partner keeps the battle going after Unnerve faints.
        val after = LocalEndTurnStateProjector.project(state(holder, unnerve, mon(BattleSide.OPPONENT, 1)))
        val cured = pokemon(after, holder)
        assertNull(cured.statusId)
        assertEquals("", cured.knownHeldItemId)
        assertEquals(0.875, cured.hpFraction, 1e-9)
        assertTrue(pokemon(after, unnerve).fainted)
        assertEquals(0.0, pokemon(after, unnerve).hpFraction, 1e-9)
    }

    @Test
    fun `active opposing Unnerve and both As One abilities stop Lum from curing Yawn`() {
        for (ability in listOf("unnerve", "asoneglastrier", "asonespectrier")) {
            val holder = mon(BattleSide.ALLY, 0, item = "lumberry")
            val after = yawn(holder, mon(BattleSide.OPPONENT, 0, ability = ability))
            assertEquals("slp", after.statusId, ability)
            assertEquals("lumberry", after.knownHeldItemId, ability)
        }
    }

    @Test
    fun `Klutz prevents Lum consumption after Yawn`() {
        val holder = mon(BattleSide.ALLY, 0, item = "lumberry", ability = "klutz")
        val after = yawn(holder, mon(BattleSide.OPPONENT, 0))
        assertEquals("slp", after.statusId)
        assertEquals("lumberry", after.knownHeldItemId)
    }

    @Test
    fun `Magic Room with time remaining prevents Lum consumption after Yawn`() {
        val holder = mon(BattleSide.ALLY, 0, item = "lumberry")
        val after = yawn(holder, mon(BattleSide.OPPONENT, 0), magicRoomTurns = 3)
        assertEquals("slp", after.statusId)
        assertEquals("lumberry", after.knownHeldItemId)
    }

    @Test
    fun `remaining Magic Room preserves poison and Lum after its residual damage`() {
        val holder = mon(BattleSide.ALLY, 0, item = "lumberry", status = "psn")
        val after = LocalEndTurnStateProjector.project(state(holder, mon(BattleSide.OPPONENT, 0), magicRoomTurns = 3))
        val unchangedStatus = pokemon(after, holder)
        assertEquals("psn", unchangedStatus.statusId)
        assertEquals("lumberry", unchangedStatus.knownHeldItemId)
        assertEquals(0.875, unchangedStatus.hpFraction, 1e-9)
        assertEquals(2, after.field.roomEffects.single().remainingTurns)
    }

    @Test
    fun `an allied Unnerve does not block its partners Lum`() {
        val holder = mon(BattleSide.ALLY, 0, item = "lumberry")
        val before = state(holder, mon(BattleSide.ALLY, 1, ability = "unnerve"), mon(BattleSide.OPPONENT, 0))
        val after = LocalEndTurnStateProjector.project(before, yawnPokemonIds = setOf(holder.battlePokemonId))
        assertNull(pokemon(after, holder).statusId)
        assertEquals("", pokemon(after, holder).knownHeldItemId)
    }

    @Test
    fun `a benched or fainted enemy Unnerve does not block Lum`() {
        for (inactive in listOf(mon(BattleSide.OPPONENT, null, ability = "unnerve"),
            mon(BattleSide.OPPONENT, 1, ability = "unnerve", hp = 0.0, fainted = true))) {
            val holder = mon(BattleSide.ALLY, 0, item = "lumberry")
            val before = state(holder, inactive, mon(BattleSide.OPPONENT, 0))
            val after = LocalEndTurnStateProjector.project(before, yawnPokemonIds = setOf(holder.battlePokemonId))
            assertNull(pokemon(after, holder).statusId)
            assertEquals("", pokemon(after, holder).knownHeldItemId)
        }
    }

    @Test
    fun `Neutralizing Gas suppressing Unnerve allows Lum to cure Yawn`() {
        val holder = mon(BattleSide.ALLY, 0, item = "lumberry")
        val before = state(holder, mon(BattleSide.ALLY, 1, ability = "neutralizinggas"),
            mon(BattleSide.OPPONENT, 0, ability = "unnerve"))
        val after = LocalEndTurnStateProjector.project(before, yawnPokemonIds = setOf(holder.battlePokemonId))
        assertNull(pokemon(after, holder).statusId)
        assertEquals("", pokemon(after, holder).knownHeldItemId)
    }

    @Test
    fun `a benched or fainted Lum holder does not eat when Magic Room expires`() {
        val bench = mon(BattleSide.ALLY, null, item = "lumberry", status = "psn")
        val fainted = mon(BattleSide.ALLY, 1, item = "lumberry", status = "psn", hp = 0.0, fainted = true)
        val after = LocalEndTurnStateProjector.project(state(mon(BattleSide.ALLY, 0), bench, fainted,
            mon(BattleSide.OPPONENT, 0), magicRoomTurns = 1))
        for (holder in listOf(bench, fainted)) {
            assertEquals("psn", pokemon(after, holder).statusId)
            assertEquals("lumberry", pokemon(after, holder).knownHeldItemId)
            assertEquals(holder.hpFraction, pokemon(after, holder).hpFraction, 1e-9)
        }
    }

    @Test
    fun `a Lum holder fainting from poison does not consume the berry when Magic Room expires`() {
        val holder = mon(BattleSide.ALLY, 0, item = "lumberry", status = "psn", hp = 0.0625)
        val after = LocalEndTurnStateProjector.project(state(holder, mon(BattleSide.ALLY, null),
            mon(BattleSide.OPPONENT, 0), magicRoomTurns = 1))
        val fainted = pokemon(after, holder)
        assertTrue(fainted.fainted)
        assertEquals(0.0, fainted.hpFraction, 1e-9)
        assertEquals("psn", fainted.statusId)
        assertEquals("lumberry", fainted.knownHeldItemId)
    }

    @Test
    fun `a healthy Lum holder and an asleep holder of another item keep their items`() {
        val healthy = mon(BattleSide.ALLY, 0, item = "lumberry")
        val otherItem = mon(BattleSide.OPPONENT, 0, item = "oranberry", status = "slp")
        val after = LocalEndTurnStateProjector.project(state(healthy, otherItem))
        assertNull(pokemon(after, healthy).statusId)
        assertEquals("lumberry", pokemon(after, healthy).knownHeldItemId)
        assertEquals("slp", pokemon(after, otherItem).statusId)
        assertEquals("oranberry", pokemon(after, otherItem).knownHeldItemId)
        assertFalse(pokemon(after, healthy).fainted)
    }

    @Test
    fun `Lum curing public confusion also removes it from the next search turn history`() {
        val holder = mon(BattleSide.ALLY, 0, item = "lumberry", volatiles = setOf("confusion"))
        val state = state(holder, mon(BattleSide.OPPONENT, 0))
        val wait = BattleActionCandidate("wait", BattleActionKind.WAIT)
        val context = BattleDecisionContext(UUID.randomUUID(), state, listOf(wait), Long.MAX_VALUE,
            BattleTacticalMemoryView.empty(), publicActionCatalog = BattlePublicActionCatalogView(emptyList()))
        val previous = RecursiveActionHistory(confusedPokemonIds = setOf(holder.battlePokemonId))
        val outcomes = PublicSingleTurnProjector.project(state, wait, wait, context, previous)
        assertTrue(outcomes.isNotEmpty())
        outcomes.forEach { outcome ->
            assertEquals("", pokemon(outcome.state, holder).knownHeldItemId)
            val next = RecursiveHistoryProjector.project(previous, state, outcome, wait, wait)
            assertFalse(holder.battlePokemonId in next.confusedPokemonIds)
        }
    }

    private fun yawn(holder: BattlePokemonStateView, foe: BattlePokemonStateView, magicRoomTurns: Int? = null) =
        pokemon(LocalEndTurnStateProjector.project(state(holder, foe, magicRoomTurns = magicRoomTurns),
            yawnPokemonIds = setOf(holder.battlePokemonId)), holder)

    private fun pokemon(state: BattleStateView, mon: BattlePokemonStateView) = state.pokemon.single { it.battlePokemonId == mon.battlePokemonId }

    private fun state(vararg pokemon: BattlePokemonStateView, magicRoomTurns: Int? = null) = BattleStateView(
        battleId = UUID.randomUUID(), format = if (pokemon.any { it.activeSlot == 1 }) BattleFormat.DOUBLE else BattleFormat.SINGLE,
        turn = 2, pokemon = pokemon.toList(), field = BattleFieldStateView(weather = null, terrain = null,
            roomEffects = magicRoomTurns?.let { listOf(BattleTimedEffectView("magicroom", it)) } ?: emptyList(), globalEffects = emptyList(),
            sideConditions = BattleSide.entries.associateWith { emptyList() }),
        remainingPokemonBySide = BattleSide.entries.associateWith { side -> pokemon.count { it.side == side && !it.fainted && it.hpFraction > 0.0 } },
        observedEvents = emptyList(), inferences = emptyList())

    private fun mon(side: BattleSide, slot: Int?, ability: String? = null, item: String? = null,
        status: String? = null, hp: Double = 1.0, fainted: Boolean = false, volatiles: Set<String> = emptySet()) = BattlePokemonStateView(
        battlePokemonId = UUID.randomUUID(), side = side, activeSlot = slot, speciesId = "showdown:probe",
        formId = null, level = 100, hpFraction = hp, statusId = status, statStages = emptyMap(), knownMoveIds = emptySet(),
        knownAbilityId = ability, knownHeldItemId = item, fainted = fainted, knownTypeIds = setOf("normal"),
        combatStats = BattleCombatStatRangesView(maxHp = BattleIntegerRange(160, 160), attack = BattleIntegerRange(100, 100),
            defence = BattleIntegerRange(100, 100), specialAttack = BattleIntegerRange(100, 100),
            specialDefence = BattleIntegerRange(100, 100), speed = BattleIntegerRange(100, 100),
            knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE),
        knownVolatileEffectIds = volatiles, knownBaseStabTypeIds = setOf("normal"))
}

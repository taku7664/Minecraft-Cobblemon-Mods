package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.betterai.mechanics.LocalAfterHitReactions
import jbro.cobblemon.mcc.betterai.mechanics.LocalDirectHitMechanics
import jbro.cobblemon.mcc.betterai.mechanics.LocalBattleStateFingerprint
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import jbro.cobblemon.mcc.betterai.state.LocalEntryAbilityProjector
import jbro.cobblemon.mcc.betterai.state.LocalEndTurnStateProjector
import jbro.cobblemon.mcc.betterai.state.LocalSwitchStateProjector
import jbro.cobblemon.mcc.internal.ai.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/** G-213 and G-218: state changes only, without adding score bonuses or changing tuning. */
class LocalCottonDownAndTraceProjectionTest {
    @Test
    fun `Cotton Down lowers every other living active Pokemon but leaves its holder and the bench alone`() {
        val attacker = mon(BattleSide.ALLY, 0)
        val ally = mon(BattleSide.ALLY, 1)
        val cotton = mon(BattleSide.OPPONENT, 0, ability = "cottondown")
        val partner = mon(BattleSide.OPPONENT, 1)
        val bench = mon(BattleSide.OPPONENT, null)
        val before = state(attacker, ally, cotton, partner, bench)
        val after = cottonHit(before, attacker, cotton)
        assertEquals(-1, stage(after, attacker, "speed"))
        assertEquals(-1, stage(after, ally, "speed"))
        assertEquals(-1, stage(after, partner, "speed"))
        assertEquals(0, stage(after, cotton, "speed"))
        assertEquals(0, stage(after, bench, "speed"))
    }

    @Test
    fun `Cotton Down still reacts to a knockout hit`() {
        val attacker = mon(BattleSide.ALLY, 0)
        val cotton = mon(BattleSide.OPPONENT, 0, ability = "cottondown")
        val before = state(attacker, cotton)
        val after = cottonHit(before, attacker, cotton, remainingHp = 0.0)
        assertEquals(-1, stage(after, attacker, "speed"))
    }

    @Test
    fun `Cotton Down does not react to a hit absorbed by Substitute or a zero damage move`() {
        val attacker = mon(BattleSide.ALLY, 0)
        val cotton = mon(BattleSide.OPPONENT, 0, ability = "cottondown")
        val before = state(attacker, cotton)
        val after = LocalAfterHitReactions.apply(before, before, attacker.battlePokemonId, cotton.battlePokemonId, attack(), 0.0)
        assertEquals(before.pokemon, after.pokemon)
    }

    @Test
    fun `Cotton Down uses the recipients Contrary Simple and drop immunity`() {
        for ((ability, item, expected) in listOf(
            Triple("contrary", null, 1), Triple("simple", null, -2), Triple("clearbody", null, 0),
            Triple("whitesmoke", null, 0), Triple("fullmetalbody", null, 0), Triple(null, "clearamulet", 0),
        )) {
            val attacker = mon(BattleSide.ALLY, 0, ability = ability, item = item)
            val cotton = mon(BattleSide.OPPONENT, 0, ability = "cottondown")
            assertEquals(expected, stage(cottonHit(state(attacker, cotton), attacker, cotton), attacker, "speed"), "$ability / $item")
        }
    }

    @Test
    fun `Cotton Down lowers another Pokemon behind Substitute without consuming that Substitute`() {
        val attacker = mon(BattleSide.ALLY, 0, volatiles = setOf("substitute"))
        val cotton = mon(BattleSide.OPPONENT, 0, ability = "cottondown")
        val after = cottonHit(state(attacker, cotton), attacker, cotton)
        assertEquals(-1, stage(after, attacker, "speed"))
        assertEquals(setOf("substitute"), pokemon(after, attacker).knownVolatileEffectIds)
    }

    @Test
    fun `Cotton Down triggers Defiant on a foe but not on its own partner`() {
        val attacker = mon(BattleSide.ALLY, 0, ability = "defiant")
        val cotton = mon(BattleSide.OPPONENT, 0, ability = "cottondown")
        val partner = mon(BattleSide.OPPONENT, 1, ability = "defiant")
        val after = cottonHit(state(attacker, cotton, partner), attacker, cotton)
        assertEquals(2, stage(after, attacker, "attack"))
        assertEquals(-1, stage(after, attacker, "speed"))
        assertEquals(0, stage(after, partner, "attack"))
        assertEquals(-1, stage(after, partner, "speed"))
    }

    @Test
    fun `Cotton Down is not a secondary effect and is not breakable by Mold Breaker`() {
        for (ability in listOf("moldbreaker", "shielddust")) {
            val attacker = mon(BattleSide.ALLY, 0, ability = ability)
            val cotton = mon(BattleSide.OPPONENT, 0, ability = "cottondown")
            assertEquals(-1, stage(cottonHit(state(attacker, cotton), attacker, cotton), attacker, "speed"), ability)
        }
    }

    @Test
    fun `suppressed Cotton Down does not lower any Speed`() {
        val attacker = mon(BattleSide.ALLY, 0, ability = "neutralizinggas")
        val cotton = mon(BattleSide.OPPONENT, 0, ability = "cottondown")
        assertEquals(0, stage(cottonHit(state(attacker, cotton), attacker, cotton), attacker, "speed"))
    }

    @Test
    fun `Trace copies a unique public copyable ability`() {
        val tracer = mon(BattleSide.ALLY, 0, ability = "trace")
        val foe = mon(BattleSide.OPPONENT, 0, ability = "levitate")
        val after = LocalEntryAbilityProjector.project(state(tracer, foe), tracer.battlePokemonId)
        assertEquals("levitate", pokemon(after, tracer).knownAbilityId)
        assertEquals("levitate", pokemon(after, foe).knownAbilityId)
    }

    @Test
    fun `Trace executes the copied entry ability`() {
        val tracer = mon(BattleSide.ALLY, 0, ability = "trace")
        val foe = mon(BattleSide.OPPONENT, 0, ability = "drought")
        val after = LocalEntryAbilityProjector.project(state(tracer, foe), tracer.battlePokemonId)
        assertEquals("drought", pokemon(after, tracer).knownAbilityId)
        assertEquals("sunnyday", after.field.weather?.effectId)
        assertEquals(5, after.field.weather?.remainingTurns)
    }

    @Test
    fun `Trace can resolve two revealed foes with the same copyable ability`() {
        val tracer = mon(BattleSide.ALLY, 0, ability = "trace")
        val after = LocalEntryAbilityProjector.project(state(tracer,
            mon(BattleSide.OPPONENT, 0, ability = "levitate"), mon(BattleSide.OPPONENT, 1, ability = "levitate")), tracer.battlePokemonId)
        assertEquals("levitate", pokemon(after, tracer).knownAbilityId)
    }

    @Test
    fun `Trace never guesses between distinct or unrevealed foe abilities`() {
        for (secondAbility in listOf("intimidate", null)) {
            val tracer = mon(BattleSide.ALLY, 0, ability = "trace")
            val after = LocalEntryAbilityProjector.project(state(tracer,
                mon(BattleSide.OPPONENT, 0, ability = "levitate"), mon(BattleSide.OPPONENT, 1, ability = secondAbility)), tracer.battlePokemonId)
            assertEquals("trace", pokemon(after, tracer).knownAbilityId, "unknown or distinct $secondAbility")
        }
    }

    @Test
    fun `Trace skips notrace abilities rather than copying a forbidden form ability`() {
        val tracer = mon(BattleSide.ALLY, 0, ability = "trace")
        val foe = mon(BattleSide.OPPONENT, 0, ability = "multitype")
        val after = LocalEntryAbilityProjector.project(state(tracer, foe), tracer.battlePokemonId)
        assertEquals("trace", pokemon(after, tracer).knownAbilityId)
        assertNull(after.field.weather)
    }

    @Test
    fun `Trace resolves a copyable foe alongside a known notrace foe`() {
        val tracer = mon(BattleSide.ALLY, 0, ability = "trace")
        val after = LocalEntryAbilityProjector.project(state(tracer,
            mon(BattleSide.OPPONENT, 0, ability = "multitype"), mon(BattleSide.OPPONENT, 1, ability = "levitate")), tracer.battlePokemonId)
        assertEquals("levitate", pokemon(after, tracer).knownAbilityId)
    }

    @Test
    fun `Trace with an active Ability Shield gives up but Magic Room disables that item`() {
        for ((magicRoom, expected) in listOf(false to "trace", true to "levitate")) {
            val tracer = mon(BattleSide.ALLY, 0, ability = "trace", item = "abilityshield")
            val before = state(tracer, mon(BattleSide.OPPONENT, 0, ability = "levitate"), magicRoom = magicRoom)
            assertEquals(expected, pokemon(LocalEntryAbilityProjector.project(before, tracer.battlePokemonId), tracer).knownAbilityId)
        }
    }

    @Test
    fun `Trace gives up if one adjacent foe has noability`() {
        val tracer = mon(BattleSide.ALLY, 0, ability = "trace")
        val after = LocalEntryAbilityProjector.project(state(tracer,
            mon(BattleSide.OPPONENT, 0, ability = "noability"), mon(BattleSide.OPPONENT, 1, ability = "levitate")), tracer.battlePokemonId)
        assertEquals("trace", pokemon(after, tracer).knownAbilityId)
    }

    @Test
    fun `Trace does not use a bench foe or an unknown dex ability`() {
        for (foe in listOf(mon(BattleSide.OPPONENT, null, ability = "levitate"),
            mon(BattleSide.OPPONENT, 0, ability = "not-a-real-ability"))) {
            val tracer = mon(BattleSide.ALLY, 0, ability = "trace")
            val after = LocalEntryAbilityProjector.project(state(tracer, foe), tracer.battlePokemonId)
            assertEquals("trace", pokemon(after, tracer).knownAbilityId)
        }
    }

    @Test
    fun `Trace reads a foes actual known ability even when Gastro Acid suppresses it`() {
        val tracer = mon(BattleSide.ALLY, 0, ability = "trace")
        val foe = mon(BattleSide.OPPONENT, 0, ability = "levitate", volatiles = setOf("gastroacid"))
        val after = LocalEntryAbilityProjector.project(state(tracer, foe), tracer.battlePokemonId)
        assertEquals("levitate", pokemon(after, tracer).knownAbilityId)
    }

    @Test
    fun `suppressed Trace does not copy its opponents ability`() {
        val tracer = mon(BattleSide.ALLY, 0, ability = "trace")
        val after = LocalEntryAbilityProjector.project(state(tracer, mon(BattleSide.OPPONENT, 0, ability = "neutralizinggas")), tracer.battlePokemonId)
        assertEquals("trace", pokemon(after, tracer).knownAbilityId)
    }

    @Test
    fun `Trace restores on switching out and copies the new foes Drought when it returns`() {
        val tracer = mon(BattleSide.ALLY, 0, ability = "trace")
        val partner = mon(BattleSide.ALLY, null)
        val levitate = mon(BattleSide.OPPONENT, 0, ability = "levitate")
        val drought = mon(BattleSide.OPPONENT, null, ability = "drought")
        val copied = LocalEntryAbilityProjector.project(state(tracer, partner, levitate, drought), tracer.battlePokemonId)
        assertEquals("levitate", pokemon(copied, tracer).knownAbilityId)
        val switchedOut = LocalSwitchStateProjector.project(copied, BattleSide.ALLY, switchTo(partner))
        assertNull(pokemon(switchedOut, tracer).activeSlot)
        assertEquals("trace", pokemon(switchedOut, tracer).knownAbilityId)
        val newFoe = LocalSwitchStateProjector.project(switchedOut, BattleSide.OPPONENT, switchTo(drought))
        val returned = LocalSwitchStateProjector.project(newFoe, BattleSide.ALLY, switchTo(tracer))
        assertEquals("drought", pokemon(returned, tracer).knownAbilityId)
        assertEquals("sunnyday", returned.field.weather?.effectId)
    }

    @Test
    fun `a copied Natural Cure heals on exit before the original Trace ability is restored`() {
        val tracer = mon(BattleSide.ALLY, 0, ability = "trace").copyState(statusId = "psn")
        val partner = mon(BattleSide.ALLY, null)
        val naturalCure = mon(BattleSide.OPPONENT, 0, ability = "naturalcure")
        val copied = LocalEntryAbilityProjector.project(state(tracer, partner, naturalCure), tracer.battlePokemonId)
        assertEquals("naturalcure", pokemon(copied, tracer).knownAbilityId)
        assertEquals("psn", pokemon(copied, tracer).statusId)
        val switchedOut = LocalSwitchStateProjector.project(copied, BattleSide.ALLY, switchTo(partner))
        assertNull(pokemon(switchedOut, tracer).statusId)
        assertEquals("trace", pokemon(switchedOut, tracer).knownAbilityId)
    }

    @Test
    fun `Trace origin survives direct damage and residual state copies before Regenerator resolves on exit`() {
        val tracer = mon(BattleSide.ALLY, 0, ability = "trace")
        val partner = mon(BattleSide.ALLY, null)
        val regenerator = mon(BattleSide.OPPONENT, 0, ability = "regenerator")
        val copied = LocalEntryAbilityProjector.project(state(tracer, partner, regenerator), tracer.battlePokemonId)
        val damaged = LocalDirectHitMechanics.apply(copied, regenerator.battlePokemonId, tracer.battlePokemonId,
            incomingDamageFraction = 0.2, effects = emptyList(), ignoreTargetAbility = false).state
        val ended = LocalEndTurnStateProjector.project(damaged)
        assertEquals("trace", pokemon(ended, tracer).knownBaseAbilityId)
        assertEquals("regenerator", pokemon(ended, tracer).knownAbilityId)
        val switchedOut = LocalSwitchStateProjector.project(ended, BattleSide.ALLY, switchTo(partner))
        assertEquals(1.0, pokemon(switchedOut, tracer).hpFraction, 1e-9)
        assertEquals("trace", pokemon(switchedOut, tracer).knownAbilityId)
    }

    @Test
    fun `cache keys distinguish the current ability and the permanent ability`() {
        val tracer = mon(BattleSide.ALLY, 0, ability = "levitate").copyState(knownBaseAbilityId = "trace")
        val before = state(tracer, mon(BattleSide.OPPONENT, 0))
        val differentBase = before.copyState(pokemon = before.pokemon.map {
            if (it.battlePokemonId == tracer.battlePokemonId) it.copyState(knownBaseAbilityId = "levitate") else it
        })
        val differentCurrent = before.copyState(pokemon = before.pokemon.map {
            if (it.battlePokemonId == tracer.battlePokemonId) it.copyState(knownAbilityId = "drought") else it
        })
        val keys = LocalBattleStateFingerprint()
        assertNotEquals(keys.of(before), keys.of(differentBase))
        assertNotEquals(keys.ofActionInputs(before), keys.ofActionInputs(differentBase))
        assertNotEquals(keys.of(before), keys.of(differentCurrent))
        assertNotEquals(keys.ofActionInputs(before), keys.ofActionInputs(differentCurrent))
    }

    private fun switchTo(mon: BattlePokemonStateView) = BattleActionCandidate(
        actionId = "switch:${mon.battlePokemonId}", kind = BattleActionKind.SWITCH, actorSlot = 0, switchPokemonId = mon.battlePokemonId)

    private fun cottonHit(before: BattleStateView, attacker: BattlePokemonStateView, cotton: BattlePokemonStateView,
        remainingHp: Double = 0.75): BattleStateView {
        val after = before.copyState(pokemon = before.pokemon.map {
            if (it.battlePokemonId == cotton.battlePokemonId) it.copyState(hpFraction = remainingHp, fainted = remainingHp <= 0.0) else it
        })
        return LocalAfterHitReactions.apply(before, after, attacker.battlePokemonId, cotton.battlePokemonId, attack(), 1.0 - remainingHp)
    }

    private fun pokemon(state: BattleStateView, mon: BattlePokemonStateView) = state.pokemon.single { it.battlePokemonId == mon.battlePokemonId }
    private fun stage(state: BattleStateView, mon: BattlePokemonStateView, stat: String) = pokemon(state, mon).statStages[stat] ?: 0

    private fun attack() = BattleActionCandidate("tackle", BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
        moveId = "tackle", targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
        moveDetails = BattleMoveCandidateView(typeId = "normal", damageCategory = BattleMoveDamageCategory.PHYSICAL,
            power = 40.0, accuracy = 100.0, priority = 0, currentPp = 10, targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT))

    private fun state(vararg pokemon: BattlePokemonStateView, magicRoom: Boolean = false) = BattleStateView(
        battleId = UUID.randomUUID(), format = if (pokemon.any { it.activeSlot == 1 }) BattleFormat.DOUBLE else BattleFormat.SINGLE,
        turn = 2, pokemon = pokemon.toList(), field = BattleFieldStateView(weather = null, terrain = null,
            roomEffects = if (magicRoom) listOf(BattleTimedEffectView("magicroom", 3)) else emptyList(), globalEffects = emptyList(),
            sideConditions = BattleSide.entries.associateWith { emptyList() }),
        remainingPokemonBySide = BattleSide.entries.associateWith { side -> pokemon.count { it.side == side && !it.fainted } },
        observedEvents = emptyList(), inferences = emptyList())

    private fun mon(side: BattleSide, slot: Int?, ability: String? = null, item: String? = null, volatiles: Set<String> = emptySet()) =
        BattlePokemonStateView(battlePokemonId = UUID.randomUUID(), side = side, activeSlot = slot, speciesId = "showdown:probe",
            formId = null, level = 100, hpFraction = 1.0, statusId = null, statStages = emptyMap(), knownMoveIds = emptySet(),
            knownAbilityId = ability, knownHeldItemId = item, fainted = false, knownTypeIds = setOf("normal"),
            knownVolatileEffectIds = volatiles, knownBaseStabTypeIds = setOf("normal"))
}

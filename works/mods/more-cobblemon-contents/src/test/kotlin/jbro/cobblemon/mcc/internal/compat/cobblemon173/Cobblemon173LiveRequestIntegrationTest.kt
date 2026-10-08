package jbro.cobblemon.mcc.internal.compat.cobblemon173

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.abilities.Abilities
import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.api.battles.model.actor.ActorType
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.experience.ExperienceGroups
import com.cobblemon.mod.common.battles.ActiveBattlePokemon
import com.cobblemon.mod.common.battles.BattleFormat
import com.cobblemon.mod.common.battles.BattleSide
import com.cobblemon.mod.common.battles.InBattleGimmickMove
import com.cobblemon.mod.common.battles.InBattleMove
import com.cobblemon.mod.common.battles.MoveTarget
import com.cobblemon.mod.common.battles.ShowdownActionRequest
import com.cobblemon.mod.common.battles.ShowdownMoveset
import com.cobblemon.mod.common.battles.ShowdownPokemon
import com.cobblemon.mod.common.battles.ShowdownSide
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import com.cobblemon.mod.common.config.CobblemonConfig
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.Species
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import java.util.UUID
import net.minecraft.SharedConstants
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/** Exercises prepare and real Cobblemon response validation with actual battle/actor/active-slot objects. */
class Cobblemon173LiveRequestIntegrationTest {
    @Test
    fun `a forced replacement keeps the healthy partner response as pass`() {
        val actor = actorWithSlots(listOf(false, true), benchAlive = listOf(true))
        actor.request = request(actor, listOf(true, false))
        val prepared = Cobblemon173ActionCandidateAdapter.prepare(actor, POLICY)
        assertEquals(Cobblemon173ActionPreparationStatus.READY, prepared.status)
        assertTrue(prepared.candidates.isNotEmpty())
        prepared.candidates.forEach { action ->
            assertEquals(listOf(BattleActionKind.SWITCH, BattleActionKind.WAIT), action.componentActions.map { it.kind })
            assertEquals(2, prepared.responsesFor(action.actionId)?.size)
        }
    }

    @Test
    fun `one reserve can legally fill either of two fainted slots`() {
        val actor = actorWithSlots(listOf(false, false), benchAlive = listOf(true))
        actor.request = request(actor, listOf(true, true))
        val prepared = Cobblemon173ActionCandidateAdapter.prepare(actor, POLICY)
        assertEquals(Cobblemon173ActionPreparationStatus.READY, prepared.status)
        assertEquals(setOf(0, 1), prepared.candidates.map { joint ->
            joint.componentActions.single { it.kind == BattleActionKind.SWITCH }.actorSlot
        }.toSet())
        assertTrue(prepared.candidates.all { it.componentActions.count { part -> part.kind == BattleActionKind.SWITCH } == 1 })
    }

    @Test
    fun `Revival Blessing allows the fainted active partner and fainted reserve`() {
        val actor = actorWithSlots(listOf(true, false), benchAlive = listOf(false, true))
        actor.request = request(actor, listOf(true, false), revivingSlot = 0)
        val prepared = Cobblemon173ActionCandidateAdapter.prepare(actor, POLICY)
        assertEquals(Cobblemon173ActionPreparationStatus.READY, prepared.status)
        val targets = prepared.candidates.map { joint ->
            joint.componentActions.single { it.kind == BattleActionKind.SWITCH }.switchPokemonId
        }.toSet()
        assertEquals(actor.pokemonList.filter { it.health == 0 }.map { it.uuid }.toSet(), targets)
    }

    @Test
    fun `initial wait traverses the actual battle adapter with zero response slots`() {
        val actor = actorWithSlots(emptyList(), emptyList())
        actor.request = ShowdownActionRequest().also { it.wait = true; it.forceSwitch = emptyList() }
        val prepared = Cobblemon173ActionCandidateAdapter.prepare(actor, POLICY)
        assertEquals(Cobblemon173ActionPreparationStatus.WAITING, prepared.status)
        assertEquals(emptyList<Any>(), prepared.responsesFor("wait"))
    }

    @Test
    fun `an already active Max Guard keeps its actual self target without reactivating Dynamax`() {
        val actor = actorWithSlots(listOf(true), emptyList())
        val foe = actor.battle.side2.actors.single()
        val foePokemon = BattlePokemon(Pokemon()).also { it.actor = foe }
        foe.pokemonList += foePokemon
        foe.activePokemon += ActiveBattlePokemon(foe, foePokemon)
        val base = InBattleMove().also {
            it.id = "thunderwave"
            it.move = "Thunder Wave"
            it.pp = 10
            it.disabled = true
            it.target = MoveTarget.normal
        }
        val max = InBattleGimmickMove().also {
            it.move = "maxguard"
            it.disabled = false
            it.target = MoveTarget.self
        }
        val moveset = ShowdownMoveset().also {
            it.moves = listOf(base)
            it.canDynamax = false
            it.maxMoves = listOf(max)
            it.setGimmickMapping()
        }
        actor.request = request(actor, emptyList()).also { it.active = mutableListOf(moveset) }

        val prepared = Cobblemon173ActionCandidateAdapter.prepare(actor, POLICY)

        assertEquals(Cobblemon173ActionPreparationStatus.READY, prepared.status)
        val action = prepared.candidates.single { it.kind == BattleActionKind.USE_MOVE }
        assertEquals(null, action.mechanic)
        val response = prepared.responsesFor(action.actionId)!!.single()
        assertTrue(response.isValid(actor.activePokemon.single(), moveset, false))
        assertEquals("move 1", response.toShowdownString(actor.activePokemon.single(), moveset))
    }

    @Test
    fun `the actual doubles request permits Beat Up on its known Justified partner`() {
        for (ability in listOf("justified", "pressure")) {
            val actor = actorWithSlots(listOf(true, true), emptyList())
            val foe = actor.battle.side2.actors.single()
            val foePokemon = BattlePokemon(Pokemon()).also { it.actor = foe }
            foe.pokemonList += foePokemon
            foe.activePokemon += ActiveBattlePokemon(foe, foePokemon)
            val beatUp = ShowdownMoveset().also { set ->
                set.moves = listOf(InBattleMove().also {
                    it.id = "beatup"; it.move = "Beat Up"; it.pp = 10; it.target = MoveTarget.normal
                })
            }
            val partner = ShowdownMoveset().also { set ->
                set.moves = listOf(InBattleMove().also {
                    it.id = "splash"; it.move = "Splash"; it.pp = 40; it.target = MoveTarget.self
                })
            }
            actor.request = request(actor, emptyList()).also {
                it.active = mutableListOf(beatUp, partner)
                it.side!!.pokemon[1].ability = ability
            }

            val prepared = Cobblemon173ActionCandidateAdapter.prepare(actor, POLICY)
            assertEquals(Cobblemon173ActionPreparationStatus.READY, prepared.status)
            val allyChoices = prepared.candidates.filter { joint ->
                joint.componentActions.any { it.moveId == "beatup" && it.targets.any { target ->
                    target.side == jbro.cobblemon.mcc.internal.ai.BattleSide.ALLY && target.slot == 1
                } }
            }
            assertEquals(ability == "justified", allyChoices.isNotEmpty(), ability)
            allyChoices.forEach { action ->
                val response = prepared.responsesFor(action.actionId)!!.first()
                assertTrue(response.isValid(actor.activePokemon[0], beatUp, false))
                assertEquals("move 1 -2", response.toShowdownString(actor.activePokemon[0], beatUp))
            }
        }
    }

    private fun actorWithSlots(activeAlive: List<Boolean>, benchAlive: List<Boolean>): BattleActor {
        val own = TestActor()
        val foe = TestActor()
        own.showdownId = "p1"
        foe.showdownId = "p2"
        PokemonBattle(BattleFormat.GEN_9_DOUBLES, BattleSide(own), BattleSide(foe))
        (activeAlive + benchAlive).forEach { alive ->
            val pokemon = BattlePokemon(Pokemon().also { it.currentHealth = if (alive) it.maxHealth else 0 })
            pokemon.actor = own
            own.pokemonList += pokemon
        }
        activeAlive.indices.forEach { own.activePokemon += ActiveBattlePokemon(own, own.pokemonList[it]) }
        return own
    }

    private fun request(actor: BattleActor, forced: List<Boolean>, revivingSlot: Int? = null) =
        ShowdownActionRequest().also { request ->
            request.forceSwitch = forced
            request.side = ShowdownSide().also { side ->
                side.pokemon = actor.pokemonList.mapIndexed { index, battlePokemon ->
                    ShowdownPokemon().also {
                        it.details = "Bulbasaur, ${battlePokemon.uuid}"
                        it.reviving = index == revivingSlot
                    }
                }
            }
        }

    private class TestActor : BattleActor(UUID.randomUUID(), mutableListOf()) {
        override val type = ActorType.NPC
        override fun getName() = Component.literal("request fixture")
        override fun nameOwned(name: String) = Component.literal(name)
    }

    companion object {
        private val POLICY = Cobblemon173MechanicPolicy(jbro.cobblemon.mcc.api.rules.BattleMechanicFlags.NONE)
        private var previousSpecies = emptyMap<ResourceLocation, Species>()

        @JvmStatic @BeforeAll
        fun registerSpecies() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
            ExperienceGroups.registerDefaults()
            if (runCatching { Cobblemon.config }.getOrNull() == null) Cobblemon.config = CobblemonConfig()
            if (Abilities.count() == 0) Abilities.register(Abilities.DUMMY)
            previousSpecies = PokemonSpecies.species.associateBy { it.resourceIdentifier }
            val species = Species().also {
                it.name = "Bulbasaur"
                it.resourceIdentifier = ResourceLocation.parse("cobblemon:bulbasaur")
                it.implemented = true
                it.initialize()
            }
            PokemonSpecies.reload(mapOf(species.resourceIdentifier to species))
        }

        @JvmStatic @AfterAll
        fun restoreSpecies() { PokemonSpecies.reload(previousSpecies) }
    }
}

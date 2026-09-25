package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatRangesView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleIntegerRange
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFieldFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleStateAdapter
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeMoveFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeTimedEffectFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NativeBattleStateAdapterTest {
    @Test
    fun `native frame becomes a complete evaluation state`() {
        val ally = pokemon(
            uuid = ALLY,
            sideSlot = 0,
            hp = 50,
            status = "brn",
            ability = "flashfire",
            item = "leftovers",
            types = listOf("Fire"),
            baseStabTypes = listOf("Bug", "Steel"),
            terastallizedType = "Fire",
            boosts = mapOf("atk" to 2),
            volatiles = listOf("confusion"),
            stats = mapOf("atk" to 121, "def" to 100, "spa" to 90, "spd" to 101, "spe" to 110),
        )
        val opponent = pokemon(
            uuid = OPPONENT,
            sideSlot = 0,
            hp = 0,
            status = "",
            ability = "levitate",
            item = "",
            types = listOf("Psychic", "Flying"),
            boosts = mapOf("spe" to -1),
            volatiles = emptyList(),
            stats = mapOf("atk" to 80, "def" to 90, "spa" to 130, "spd" to 120, "spe" to 95),
        )
        val frame = NativeBattleFrame(
            snapshotJson = "{}",
            turn = 3,
            requestState = "switch",
            ended = false,
            p1Active = listOf(ally),
            p2Active = listOf(opponent),
            p1Team = listOf(ally),
            p2Team = listOf(opponent),
            p1RequestJson = "{\"wait\":true}",
            p2RequestJson = "{\"forceSwitch\":[true]}",
            field = NativeBattleFieldFrame(
                weather = NativeTimedEffectFrame("raindance", 3, null),
                terrain = null,
                pseudoWeather = listOf(
                    NativeTimedEffectFrame("trickroom", 4, null),
                    NativeTimedEffectFrame("gravity", 2, null),
                ),
                p1SideConditions = listOf(NativeTimedEffectFrame("reflect", 5, null)),
                p2SideConditions = emptyList(),
            ),
            log = emptyList(),
        )

        val state = NativeBattleStateAdapter.adapt(frame, template())
        val adaptedAlly = state.pokemon.single { it.battlePokemonId == ALLY }
        val adaptedOpponent = state.pokemon.single { it.battlePokemonId == OPPONENT }

        assertEquals(3, state.turn)
        assertEquals(0.5, adaptedAlly.hpFraction)
        assertEquals("brn", adaptedAlly.statusId)
        assertEquals(2, adaptedAlly.statStages["atk"])
        assertEquals(setOf("confusion"), adaptedAlly.knownVolatileEffectIds)
        assertEquals(setOf("fire"), adaptedAlly.knownTypeIds)
        assertEquals(setOf("bug", "steel"), adaptedAlly.knownBaseStabTypeIds)
        assertEquals("fire", adaptedAlly.knownTeraTypeId)
        assertEquals("flashfire", adaptedAlly.knownAbilityId)
        assertEquals("leftovers", adaptedAlly.knownHeldItemId)
        assertEquals(121, adaptedAlly.combatStats?.attack?.minimum)
        assertEquals(BattleCombatStatKnowledge.EXACT_OWN, adaptedAlly.combatStats?.knowledge)
        assertEquals(BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE, adaptedOpponent.combatStats?.knowledge)
        assertEquals(true, adaptedOpponent.fainted)
        assertNull(adaptedOpponent.activeSlot)
        assertEquals(1, state.remainingPokemonBySide.getValue(BattleSide.ALLY))
        assertEquals(0, state.remainingPokemonBySide.getValue(BattleSide.OPPONENT))
        assertEquals("raindance", state.field.weather?.effectId)
        assertEquals(3, state.field.weather?.remainingTurns)
        assertEquals(listOf("trickroom"), state.field.roomEffects.map { it.effectId })
        assertEquals(listOf("gravity"), state.field.globalEffects.map { it.effectId })
        assertEquals(
            listOf("reflect"),
            state.field.sideConditions.getValue(BattleSide.ALLY).map { it.effectId },
        )
    }

    private fun template(): BattleStateView = BattleStateView(
        battleId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(
            templatePokemon(ALLY, BattleSide.ALLY, 0, BattleCombatStatKnowledge.EXACT_OWN),
            templatePokemon(OPPONENT, BattleSide.OPPONENT, 0, BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE),
        ),
        field = BattleFieldStateView.empty(),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1),
        observedEvents = emptyList(),
        inferences = emptyList(),
    )

    private fun templatePokemon(
        uuid: UUID,
        side: BattleSide,
        activeSlot: Int,
        knowledge: BattleCombatStatKnowledge,
    ) = BattlePokemonStateView(
        battlePokemonId = uuid,
        side = side,
        activeSlot = activeSlot,
        speciesId = "cobblemon:mew",
        formId = null,
        level = 50,
        hpFraction = 1.0,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = setOf("cobblemon:tackle"),
        knownAbilityId = null,
        knownHeldItemId = null,
        fainted = false,
        knownTypeIds = setOf("psychic"),
        combatStats = stats(knowledge),
        knownVolatileEffectIds = emptySet(),
    )

    private fun pokemon(
        uuid: UUID,
        sideSlot: Int?,
        hp: Int,
        status: String,
        ability: String,
        item: String,
        types: List<String>,
        baseStabTypes: List<String> = types,
        terastallizedType: String = "",
        boosts: Map<String, Int>,
        volatiles: List<String>,
        stats: Map<String, Int>,
    ) = NativePokemonFrame(
        uuid = uuid.toString(),
        species = "mew",
        hp = hp,
        maxHp = 100,
        status = status,
        ability = ability,
        item = item,
        types = types,
        boosts = boosts,
        volatiles = volatiles,
        moves = listOf(NativeMoveFrame("tackle", 35, 35, false)),
        activeSlot = sideSlot,
        level = 50,
        stats = stats,
        baseStabTypes = baseStabTypes,
        terastallizedType = terastallizedType,
    )

    private fun stats(knowledge: BattleCombatStatKnowledge) = BattleCombatStatRangesView(
        maxHp = BattleIntegerRange(100, 100),
        attack = BattleIntegerRange(100, 100),
        defence = BattleIntegerRange(100, 100),
        specialAttack = BattleIntegerRange(100, 100),
        specialDefence = BattleIntegerRange(100, 100),
        speed = BattleIntegerRange(100, 100),
        knowledge = knowledge,
    )

    private companion object {
        val ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val OPPONENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000201")
    }
}

package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicItemState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LocalPublicItemStateTest {
    @Test
    fun `klutz suppresses ordinary items but preserves the declared ignore klutz set`() {
        assertNull(LocalPublicItemState.activeItemId(state("leftovers"), pokemon("leftovers")))
        listOf(
            "abilityshield", "machobrace", "poweranklet", "powerband",
            "powerbelt", "powerbracer", "powerlens", "powerweight",
        ).forEach { item ->
            assertEquals(item, LocalPublicItemState.activeItemId(state(item), pokemon(item)), item)
        }
    }

    @Test
    fun `magic room suppresses even an item that ignores klutz`() {
        val pokemon = pokemon("abilityshield")
        val state = state("abilityshield", magicRoom = true)

        assertNull(LocalPublicItemState.activeItemId(state, pokemon))
    }

    private fun state(item: String, magicRoom: Boolean = false) = BattleStateView(
        battleId = BATTLE_ID,
        format = BattleFormat.SINGLE,
        turn = 1,
        pokemon = listOf(pokemon(item)),
        field = BattleFieldStateView(
            weather = null,
            terrain = null,
            roomEffects = if (magicRoom) listOf(BattleTimedEffectView("magicroom", 3)) else emptyList(),
            globalEffects = emptyList(),
            sideConditions = BattleSide.entries.associateWith { emptyList() },
        ),
        remainingPokemonBySide = mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 0),
        observedEvents = emptyList(),
        inferences = emptyList(),
    )

    private fun pokemon(item: String) = BattlePokemonStateView(
        battlePokemonId = POKEMON_ID,
        side = BattleSide.ALLY,
        activeSlot = 0,
        speciesId = "cobblemon:probe",
        formId = null,
        level = 50,
        hpFraction = 1.0,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = emptySet(),
        knownAbilityId = "cobblemon:klutz",
        knownHeldItemId = "cobblemon:$item",
        fainted = false,
        knownTypeIds = setOf("normal"),
        combatStats = BattleCombatStatRangesView.exact(100, 100, 100, 100, 100, 100),
    )

    private companion object {
        val BATTLE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000711")
        val POKEMON_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000712")
    }
}

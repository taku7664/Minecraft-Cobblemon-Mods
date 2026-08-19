package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryRentalSet
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryStatSpread
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class Cobblemon173FactoryObservationMapperTest {
    @Test
    fun `maps showdown public ids back to exact catalog ids without revealing unknown fields`() {
        val token = UUID.randomUUID()
        val battlePokemonId = UUID.randomUUID()
        val rental = rental()

        val observed = Cobblemon173FactoryObservationMapper.map(
            rentalsByToken = mapOf(token to rental),
            battlePokemonIdsByToken = mapOf(token to battlePokemonId),
            publicPokemon = listOf(
                state(
                    battlePokemonId,
                    knownMoves = setOf("surf"),
                    knownAbility = "naturalcure",
                    knownItem = null,
                ),
            ),
        )

        assertEquals(setOf("cobblemon:surf"), observed.getValue("factory_starmie").revealedMoveIds)
        assertEquals("cobblemon:natural_cure", observed.getValue("factory_starmie").revealedAbilityId)
        assertEquals(null, observed.getValue("factory_starmie").revealedHeldItemId)
    }

    @Test
    fun `ignores public pokemon that are not part of the prepared opponent rental team`() {
        val token = UUID.randomUUID()
        val battlePokemonId = UUID.randomUUID()

        val observed = Cobblemon173FactoryObservationMapper.map(
            rentalsByToken = mapOf(token to rental()),
            battlePokemonIdsByToken = mapOf(token to battlePokemonId),
            publicPokemon = listOf(state(UUID.randomUUID(), setOf("surf"), "naturalcure", "lumberry")),
        )

        assertEquals(emptyMap<String, Any>(), observed)
    }

    private fun state(
        id: UUID,
        knownMoves: Set<String>,
        knownAbility: String?,
        knownItem: String?,
    ) = BattlePokemonStateView(
        battlePokemonId = id,
        side = BattleSide.OPPONENT,
        activeSlot = null,
        speciesId = "cobblemon:starmie",
        formId = null,
        level = 50,
        hpFraction = 0.0,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = knownMoves,
        knownAbilityId = knownAbility,
        knownHeldItemId = knownItem,
        fainted = true,
    )

    private fun rental() = FactoryRentalSet(
        setId = "factory_starmie",
        speciesId = "cobblemon:starmie",
        moveIds = listOf("cobblemon:surf", "cobblemon:ice_beam"),
        abilityId = "cobblemon:natural_cure",
        heldItemId = "cobblemon:lum_berry",
        natureId = "cobblemon:timid",
        ivs = FactoryStatSpread(31, 31, 31, 31, 31, 31),
        evs = FactoryStatSpread(0, 0, 0, 0, 0, 0),
    )
}

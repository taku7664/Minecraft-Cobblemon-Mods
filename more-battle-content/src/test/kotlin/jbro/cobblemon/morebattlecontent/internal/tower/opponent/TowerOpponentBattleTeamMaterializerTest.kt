package jbro.cobblemon.morebattlecontent.internal.tower.opponent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TowerOpponentBattleTeamMaterializerTest {
    @Test
    fun `creates every member in selected order`() {
        val sets = listOf(pokemonSet("first"), pokemonSet("second"), pokemonSet("third"))
        val materializer = TowerOpponentBattleTeamMaterializer<String> { "created:${it.setId}" }

        val result = materializer.materialize(sets)

        result as TowerOpponentBattleTeamMaterialization.Created
        assertEquals(listOf("created:first", "created:second", "created:third"), result.members)
    }

    @Test
    fun `creation failure reports the set and never returns a partial team`() {
        val sets = listOf(pokemonSet("first"), pokemonSet("second"), pokemonSet("third"))
        val calls = ArrayList<String>()
        val materializer = TowerOpponentBattleTeamMaterializer<String> { set ->
            calls.add(set.setId)
            if (set.setId == "second") throw IllegalStateException("failed")
            "created:${set.setId}"
        }

        val result = materializer.materialize(sets)

        assertTrue(result is TowerOpponentBattleTeamMaterialization.CreationFailed)
        result as TowerOpponentBattleTeamMaterialization.CreationFailed
        assertEquals("second", result.setId)
        assertEquals(listOf("first", "second"), calls)
    }

    private fun pokemonSet(id: String) = TowerPokemonSet(
        setId = id,
        setTier = 1,
        speciesId = "cobblemon:$id",
        formId = null,
        abilityId = null,
        natureId = "cobblemon:hardy",
        heldItemId = null,
        moves = listOf("cobblemon:tackle"),
        ivs = TowerStatSpread(15, 15, 15, 15, 15, 15),
        evs = TowerStatSpread(0, 0, 0, 0, 0, 0),
    )
}

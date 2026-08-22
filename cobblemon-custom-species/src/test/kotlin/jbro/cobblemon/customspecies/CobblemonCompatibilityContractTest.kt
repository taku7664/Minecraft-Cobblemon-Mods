package jbro.cobblemon.customspecies

import com.cobblemon.mod.common.api.abilities.AbilityPool
import com.cobblemon.mod.common.api.pokemon.moves.Learnset
import com.cobblemon.mod.common.pokemon.FormData
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CobblemonCompatibilityContractTest {
    @Test
    fun `cobblemon 173 form inheritance fields remain available behind the isolated bridge`() {
        val moves = FormData::class.java.getDeclaredField("_moves")
        val abilities = FormData::class.java.getDeclaredField("_abilities")
        val stats = FormData::class.java.getDeclaredField("_baseStats")

        assertEquals(Learnset::class.java, moves.type)
        assertEquals(AbilityPool::class.java, abilities.type)
        assertTrue(Map::class.java.isAssignableFrom(stats.type))
        assertTrue(moves.trySetAccessible())
        assertTrue(abilities.trySetAccessible())
        assertTrue(stats.trySetAccessible())
    }

    @Test
    fun `fabric metadata stays independent from more battle content and allows newer cobblemon`() {
        val source = requireNotNull(javaClass.getResourceAsStream("/fabric.mod.json"))
            .bufferedReader().use { it.readText() }
        val metadata = JsonParser.parseString(source).asJsonObject
        val dependencies = metadata.getAsJsonObject("depends")

        assertEquals("cobblemon_custom_species", metadata.get("id").asString)
        assertEquals("*", metadata.get("environment").asString)
        assertEquals(">=1.7.3", dependencies.get("cobblemon").asString)
        assertTrue(dependencies.keySet().none { it.contains("more_battle_content") })
    }
}

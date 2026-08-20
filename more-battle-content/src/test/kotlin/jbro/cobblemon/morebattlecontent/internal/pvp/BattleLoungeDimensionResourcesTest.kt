package jbro.cobblemon.morebattlecontent.internal.pvp

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattleLoungeDimensionResourcesTest {
    @Test
    fun `the lounge uses its own biome so it is not labelled as the void`() {
        val dimension = JsonParser
            .parseString(resource("/data/cobblemon_more_battle_content/dimension/battle_lounge.json"))
            .asJsonObject

        val biome = dimension
            .getAsJsonObject("generator")
            .getAsJsonObject("settings")["biome"]
            .asString

        assertEquals(BIOME_ID, biome)
    }

    @Test
    fun `the lounge biome is packaged and generates nothing on its own`() {
        val biome = JsonParser
            .parseString(resource("/data/cobblemon_more_battle_content/worldgen/biome/battle_lounge.json"))
            .asJsonObject

        assertTrue(biome.getAsJsonArray("features").all { it.asJsonArray.isEmpty })
        biome.getAsJsonObject("spawners").entrySet().forEach { (category, entries) ->
            assertTrue(entries.asJsonArray.isEmpty, "$category must not spawn anything inside an arena")
        }
        assertFalse(biome["has_precipitation"].asBoolean)
    }

    @Test
    fun `both languages name the lounge biome`() {
        listOf("en_us", "ko_kr").forEach { language ->
            val translations = JsonParser
                .parseString(resource("/assets/cobblemon_more_battle_content/lang/$language.json"))
                .asJsonObject
            val name = translations[BIOME_TRANSLATION_KEY]

            assertNotNull(name, "$language is missing the lounge biome name")
            assertTrue(name.asString.isNotBlank(), "$language has a blank lounge biome name")
        }
    }

    private fun resource(path: String): String {
        val stream = javaClass.getResourceAsStream(path)
        assertNotNull(stream, "$path must be packaged")
        return stream!!.bufferedReader().use { it.readText() }
    }

    private companion object {
        const val BIOME_ID = "cobblemon_more_battle_content:battle_lounge"
        const val BIOME_TRANSLATION_KEY = "biome.cobblemon_more_battle_content.battle_lounge"
    }
}

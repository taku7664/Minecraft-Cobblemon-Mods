package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@EnabledIfSystemProperty(named = "betterai.oracle", matches = "true")
class EmbeddedPresetAuditTest {
    @Test
    fun `audit accounts for every raw preset including rejected moves`(@TempDir directory: Path) {
        val sets = EmbeddedPresetAudit.rawSets()
        val broken = sets[0].asJsonObject.deepCopy().apply {
            addProperty("set_id", "invalid_move_control")
            getAsJsonArray("moves").set(0, com.google.gson.JsonPrimitive("cobblemon:notarealmove"))
        }
        sets.add(broken)
        val result = EmbeddedPresetAudit.run(directory, sets)
        val rows = result.getAsJsonArray("sets").map { it.asJsonObject }
        assertEquals(sets.size(), rows.size)
        assertEquals(sets.map { it.asJsonObject["set_id"].asString }.toSet(),
            rows.map { it["setId"].asString }.toSet())
        val rejected = rows.single { it["setId"].asString == "invalid_move_control" }
        assertFalse(rejected["registryCompatible"].asBoolean)
        assertFalse(rejected["obtainableChecked"].asBoolean)
        assertTrue(rejected.getAsJsonArray("registryProblems").toString().contains("notarealmove"))
        assertTrue(rows.any { it["obtainable"].asBoolean })
        assertEquals(sets.size(), result["total"].asInt)
        assertEquals(0, result["initializationErrors"].asInt)
        assertEquals(result["registryCompatible"].asInt, result["initialized"].asInt)
        val arceus = rows.single { it["setId"].asString == "arceusbug_preset_1" }
        assertEquals("Arceus-Bug", arceus.getAsJsonObject("engineSet")["species"].asString)
        assertTrue(arceus.getAsJsonObject("initialization")["speciesMatches"].asBoolean)
        assertFalse(arceus.getAsJsonObject("initialization")["typesMatch"].asBoolean)
        assertEquals(listOf("Normal"), arceus.getAsJsonObject("initialization").getAsJsonArray("types").map { it.asString })
    }

    @Test
    fun `existence is distinct from obtainability and validation never repairs source`(@TempDir directory: Path) {
        val legal = JsonParser.parseString("""{
          "set_id":"control", "species_id":"cobblemon:pikachu",
          "ability_id":"cobblemon:static", "held_item_id":"cobblemon:light_ball",
          "nature_id":"cobblemon:timid", "moves":["cobblemon:thunderbolt","cobblemon:quickattack","cobblemon:protect","cobblemon:thunderwave"],
          "evs":{"hp":4,"attack":0,"defense":0,"special_attack":252,"special_defense":0,"speed":252}
        }""").asJsonObject
        val impossible = legal.deepCopy().apply {
            addProperty("set_id", "illegal_ability_control")
            addProperty("ability_id", "cobblemon:wonderguard")
        }
        val sets = JsonArray().apply { add(legal); add(impossible) }
        val before = sets.deepCopy()
        val first = EmbeddedPresetAudit.run(directory.resolve("first"), sets)
        val replay = EmbeddedPresetAudit.run(directory.resolve("replay"), sets)
        assertEquals(first, replay, "Omitted catalog gender must not make validation random")
        val rows = first.getAsJsonArray("sets").map { it.asJsonObject }
        assertEquals("M", rows[0].getAsJsonObject("engineSet")["gender"].asString)
        assertTrue(rows[0]["obtainable"].asBoolean)
        assertTrue(rows[1]["registryCompatible"].asBoolean)
        assertFalse(rows[1]["obtainable"].asBoolean)
        assertTrue(rows[1].getAsJsonArray("obtainableProblems").toString().contains("Wonder Guard"))
        assertEquals(before, sets)
        assertEquals("Wonder Guard", rows[1].getAsJsonObject("engineSet")["ability"].asString)
    }
}

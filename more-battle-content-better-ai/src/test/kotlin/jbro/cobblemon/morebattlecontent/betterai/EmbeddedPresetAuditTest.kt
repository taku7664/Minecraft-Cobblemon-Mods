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
    fun `native corpus partitions are disjoint reproducible and match independent keys`(@TempDir directory: Path) {
        val audits = EvaluationSplit.entries.associateWith { split ->
            EmbeddedPresetAudit.run(directory.resolve(split.name), teamPairs = 20, teamSplit = split)
        }
        val keys = audits.mapValues { (split, audit) ->
            val sampling = audit.getAsJsonObject("teamSampling")
            assertEquals(split.name, sampling["partition"].asString)
            sampling.getAsJsonArray("pairs").map { value ->
                val pair = value.asJsonObject
                assertEquals(EmbeddedNativeCorpus.key(pair), pair["corpusKey"].asString)
                assertEquals(split, EmbeddedNativeCorpus.split(pair))
                assertEquals(split.name, pair["partition"].asString)
                pair["corpusKey"].asString
            }.also { assertEquals(20, it.size) }.toSet().also { assertEquals(20, it.size) }
        }
        assertTrue(keys.getValue(EvaluationSplit.TUNING).intersect(keys.getValue(EvaluationSplit.HOLDOUT)).isEmpty())
        assertEquals(audits.getValue(EvaluationSplit.HOLDOUT), EmbeddedPresetAudit.run(
            directory.resolve("replay"), teamPairs = 20, teamSplit = EvaluationSplit.HOLDOUT))
        val smallPool = JsonArray().apply {
            EmbeddedPresetAudit.rawSets().filter { it.asJsonObject["set_id"].asString in
                setOf("golem_preset_1", "decidueyehisui_preset_4", "delphox_preset_4") }.forEach(::add)
        }
        val exhausted = assertThrows(IllegalStateException::class.java) {
            EmbeddedPresetAudit.run(directory.resolve("exhausted"), smallPool, teamPairs = 2, teamSplit = EvaluationSplit.TUNING)
        }
        assertTrue(exhausted.message!!.contains("Cannot draw enough distinct pairs in corpus partition"))
    }

    @Test
    fun `audit accounts for every raw preset including rejected moves`(@TempDir directory: Path) {
        val sets = EmbeddedPresetAudit.rawSets()
        val broken = sets[0].asJsonObject.deepCopy().apply {
            addProperty("set_id", "invalid_move_control")
            getAsJsonArray("moves").set(0, com.google.gson.JsonPrimitive("cobblemon:notarealmove"))
        }
        sets.add(broken)
        val result = EmbeddedPresetAudit.run(directory, sets, teamPairs = 100)
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
        val sampling = result.getAsJsonObject("teamSampling")
        assertEquals(100, sampling.getAsJsonArray("pairs").size())
        assertEquals(0, sampling["rejectedTeams"].asInt)
        val teams = sampling.getAsJsonArray("pairs").flatMap { pair ->
            listOf(pair.asJsonObject.getAsJsonObject("p1"), pair.asJsonObject.getAsJsonObject("p2"))
        }
        val accepted = rows.filter { it["obtainable"].asBoolean }.associateBy { it["setId"].asString }
        teams.forEach { team ->
            val ids = team.getAsJsonArray("setIds").map { it.asString }
            assertEquals(3, ids.size)
            assertEquals(3, ids.distinct().size)
            assertTrue(team.getAsJsonArray("problems").isEmpty)
            assertEquals(3, team["initializedCount"].asInt)
            ids.forEachIndexed { index, id ->
                assertEquals(accepted.getValue(id)["engineSet"], team.getAsJsonArray("sets")[index])
            }
        }
        assertEquals(200, teams.map { it["setIds"].toString() }.distinct().size,
            "This fixed-seed sample must draw fresh teams, not reuse a toy roster")
        val replay = EmbeddedPresetAudit.run(directory.resolve("replay"), sets, teamPairs = 100)
        assertEquals(result, replay, "Team sets, validation and battle seeds must replay exactly")
        val different = EmbeddedPresetAudit.run(directory.resolve("different"), sets, teamPairs = 1, teamSeed = 9)
        assertEquals(result["catalogSha256"], different["catalogSha256"])
        assertNotEquals(sampling.getAsJsonArray("pairs")[0],
            different.getAsJsonObject("teamSampling").getAsJsonArray("pairs")[0])
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
        val failure = assertThrows(IllegalStateException::class.java) {
            EmbeddedPresetAudit.run(directory.resolve("insufficient"), sets, teamPairs = 1)
        }
        assertTrue(failure.message!!.contains("Cannot draw three distinct species/items"))
        assertEquals(before, sets, "Insufficient eligible data must fail, never repair source or duplicate a Pokemon")
    }
}

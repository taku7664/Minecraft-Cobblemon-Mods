package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

/** Level-50 bring-six-pick-three mirrors using a curated Battle Stadium Singles sample entry. */
internal object EmbeddedBssTournamentMirrorBattle {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..4) { "Expected output directory, seeds per lead, repeats and max turns" }
        val directory = Path.of(args[0]).toAbsolutePath().normalize()
        val seedsPerLead = args.getOrNull(1)?.toInt() ?: 2
        val repeats = args.getOrNull(2)?.toInt() ?: 1
        val maxTurns = args.getOrNull(3)?.toInt() ?: 100
        require(seedsPerLead in 1..20 && repeats in 1..5 && maxTurns in 1..1000)
        Files.createDirectories(directory.parent)
        Files.createDirectory(directory)

        EmbeddedPresetAudit.run(directory.resolve("audit"), teamPairs = 0)
        val engine = directory.resolve("audit/engine")
        val games = JsonArray()
        for (lead in SELECTED_INDICES.indices) {
            for (seedIndex in 0 until seedsPerLead) {
                for (repeat in 0 until repeats) {
                    val gameDirectory = directory.resolve("lead-$lead-seed-$seedIndex-run-$repeat")
                    val battlePair = pair(lead, seedIndex)
                    val result = EmbeddedTeamBattle.run(engine, battlePair, gameDirectory, maxTurns)
                    val decisions = Files.readAllLines(gameDirectory.resolve("decisions.jsonl"))
                    val firstBySide = decisions.asSequence()
                        .map { JsonParser.parseString(it).asJsonObject }
                        .groupBy { it["side"].asString }
                        .mapValues { (_, traces) -> traces.first()["actionId"].asString }
                    games.add(JsonObject().apply {
                        addProperty("leadIndex", lead)
                        addProperty("leadSpecies", selectedTeam(lead).getAsJsonArray("sets")[0].asJsonObject["species"].asString)
                        addProperty("seedIndex", seedIndex)
                        addProperty("repeat", repeat)
                        add("battleSeed", battlePair["battleSeed"].deepCopy())
                        addProperty("status", result["status"].asString)
                        add("winner", result["winner"].deepCopy())
                        addProperty("turn", result["turn"].asInt)
                        addProperty("decisions", result["decisions"].asInt)
                        addProperty("p1FirstAction", firstBySide["p1"])
                        addProperty("p2FirstAction", firstBySide["p2"])
                        addProperty("sameFirstAction", firstBySide["p1"] == firstBySide["p2"])
                        addProperty("result", directory.relativize(gameDirectory.resolve("result.json")).toString())
                    })
                }
            }
        }

        val records = games.map { it.asJsonObject }
        val summary = JsonObject().apply {
            addProperty("schemaVersion", 1)
            addProperty("source", "Smogon curated BSS sample: Banded Koraidon + Scarf Calyrex Offense")
            addProperty("sourceUrl", "https://www.smogon.com/forums/threads/battle-stadium-singles-sample-teams-all-gens.3746122/")
            addProperty("teamUrl", "https://psim.us/t/1552692")
            addProperty("comparisonTournament", "NONE; curated sample mirror only")
            addProperty("comparisonReplay", "NONE")
            addProperty("format", "BSS battle phase; level 50; same selected 3 on both sides; Tera unavailable to adapter")
            addProperty("selectionBoundary", "MANUAL_PUBLISHED_CORE_SELECTION;NO_TEAM_PREVIEW_SELECTION_AI")
            addProperty("seedsPerLead", seedsPerLead)
            addProperty("repeats", repeats)
            addProperty("games", records.size)
            addProperty("complete", records.count { it["status"].asString == "COMPLETE" })
            addProperty("turnLimited", records.count { it["status"].asString == "TURN_LIMIT" })
            addProperty("p1Wins", records.count { it["winner"]?.takeUnless { value -> value.isJsonNull }?.asString == "p1" })
            addProperty("p2Wins", records.count { it["winner"]?.takeUnless { value -> value.isJsonNull }?.asString == "p2" })
            addProperty("sameFirstAction", records.count { it["sameFirstAction"].asBoolean })
            addProperty("evidence", "NATIVE_SHOWDOWN_ENGINE_LOCAL_BRAIN_BSS_BATTLE_PHASE_PARTIAL_INPUT_ADAPTER")
            add("entry", entry())
            add("selected", selectedTeam(0))
            add("results", games)
        }
        Files.writeString(directory.resolve("summary.json"), GsonBuilder().setPrettyPrinting().create().toJson(summary), CREATE_NEW)
        println(
            "BSS_TOURNAMENT_MIRROR games=${records.size} complete=${summary["complete"]} " +
                "p1=${summary["p1Wins"]} p2=${summary["p2Wins"]} sameFirst=${summary["sameFirstAction"]}",
        )
        println("BSS tournament mirror capture: $directory")
    }

    fun pair(leadIndex: Int, seedIndex: Int): JsonObject {
        require(leadIndex in SELECTED_INDICES.indices && seedIndex >= 0)
        val selected = selectedTeam(leadIndex)
        return JsonObject().apply {
            addProperty("battleFormat", "SINGLE")
            add("battleSeed", JsonArray().apply {
                listOf(2026, 9, 23, 10_000 + leadIndex * 100 + seedIndex + 1).forEach(::add)
            })
            add("p1", selected.deepCopy())
            add("p2", selected.deepCopy())
        }
    }

    fun entry(): JsonObject = team(SETS)

    private fun selectedTeam(leadIndex: Int): JsonObject {
        val selected = SELECTED_INDICES.map(SETS::get)
        val rotated = selected.drop(leadIndex) + selected.take(leadIndex)
        return team(rotated)
    }

    private fun team(sets: List<JsonObject>) = JsonObject().apply {
        add("setIds", JsonArray().apply { sets.forEach { add(it["species"].asString) } })
        add("sets", JsonArray().apply { sets.forEach { add(it.deepCopy()) } })
    }

    private fun set(
        species: String,
        item: String,
        ability: String,
        teraType: String,
        nature: String,
        evs: Map<String, Int>,
        moves: List<String>,
        ivs: Map<String, Int> = emptyMap(),
    ) = JsonObject().apply {
        addProperty("species", species)
        addProperty("item", item)
        addProperty("ability", ability)
        addProperty("teraType", teraType)
        addProperty("nature", nature)
        addProperty("level", 50)
        add("moves", JsonArray().apply { moves.forEach(::add) })
        add("evs", stats(0, evs))
        add("ivs", stats(31, ivs))
    }

    private fun stats(default: Int, values: Map<String, Int>) = JsonObject().apply {
        STAT_IDS.forEach { addProperty(it, values[it] ?: default) }
    }

    private val SELECTED_INDICES = listOf(0, 1, 2)
    private val STAT_IDS = listOf("hp", "atk", "def", "spa", "spd", "spe")
    private val SETS = listOf(
        set(
            "Koraidon", "Choice Band", "Orichalcum Pulse", "Fire", "Jolly",
            mapOf("hp" to 4, "atk" to 252, "spe" to 252),
            listOf("Flare Blitz", "Close Combat", "Outrage", "U-turn"),
        ),
        set(
            "Calyrex-Shadow", "Choice Scarf", "As One (Spectrier)", "Fairy", "Timid",
            mapOf("hp" to 140, "def" to 12, "spa" to 196, "spd" to 4, "spe" to 156),
            listOf("Astral Barrage", "Psychic", "Trick", "Draining Kiss"),
            mapOf("atk" to 0),
        ),
        set(
            "Flutter Mane", "Choice Specs", "Protosynthesis", "Normal", "Timid",
            mapOf("def" to 4, "spa" to 252, "spe" to 252),
            listOf("Moonblast", "Shadow Ball", "Perish Song", "Power Gem"),
            mapOf("atk" to 0),
        ),
        set(
            "Chien-Pao", "Focus Sash", "Sword of Ruin", "Electric", "Jolly",
            mapOf("atk" to 252, "def" to 4, "spe" to 252),
            listOf("Icicle Crash", "Sucker Punch", "Swords Dance", "Tera Blast"),
        ),
        set(
            "Landorus-Therian", "Rocky Helmet", "Intimidate", "Water", "Impish",
            mapOf("hp" to 252, "atk" to 4, "def" to 252),
            listOf("Earthquake", "Rock Tomb", "Taunt", "U-turn"),
        ),
        set(
            "Ting-Lu", "Assault Vest", "Vessel of Ruin", "Steel", "Adamant",
            mapOf("hp" to 36, "atk" to 252, "spd" to 220),
            listOf("Earthquake", "Ruination", "Heavy Slam", "Payback"),
        ),
    )
}

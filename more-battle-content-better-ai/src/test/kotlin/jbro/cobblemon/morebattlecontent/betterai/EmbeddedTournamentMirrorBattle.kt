package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

/** Native Better AI mirror matches using Cow's published 2025 WCoP SV OU team. */
internal object EmbeddedTournamentMirrorBattle {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..4) { "Expected output directory, seeds per lead, repeats and max turns" }
        val directory = Path.of(args[0]).toAbsolutePath().normalize()
        val seedsPerLead = args.getOrNull(1)?.toInt() ?: 2
        val repeats = args.getOrNull(2)?.toInt() ?: 2
        val maxTurns = args.getOrNull(3)?.toInt() ?: 300
        require(seedsPerLead in 1..20 && repeats in 1..5 && maxTurns in 1..1000)
        Files.createDirectories(directory.parent)
        Files.createDirectory(directory)

        EmbeddedPresetAudit.run(directory.resolve("audit"), teamPairs = 0)
        val engine = directory.resolve("audit/engine")
        val games = JsonArray()
        for (lead in 0 until TEAM_SIZE) {
            for (seedIndex in 0 until seedsPerLead) {
                for (repeat in 0 until repeats) {
                    val gameDirectory = directory.resolve("lead-$lead-seed-$seedIndex-run-$repeat")
                    val result = EmbeddedTeamBattle.run(
                        engine = engine,
                        pair = pair(lead, seedIndex),
                        directory = gameDirectory,
                        maxTurns = maxTurns,
                    )
                    val decisions = Files.readAllLines(gameDirectory.resolve("decisions.jsonl"))
                    val firstBySide = decisions.asSequence()
                        .map { JsonParser.parseString(it).asJsonObject }
                        .groupBy { it["side"].asString }
                        .mapValues { (_, traces) -> traces.first()["actionId"].asString }
                    games.add(JsonObject().apply {
                        addProperty("leadIndex", lead)
                        addProperty("leadSpecies", team(lead).getAsJsonArray("sets")[0].asJsonObject["species"].asString)
                        addProperty("seedIndex", seedIndex)
                        addProperty("repeat", repeat)
                        add("battleSeed", pair(lead, seedIndex)["battleSeed"].deepCopy())
                        addProperty("status", result["status"].asString)
                        add("winner", result["winner"].deepCopy())
                        addProperty("turn", result["turn"].asInt)
                        addProperty("decisions", result["decisions"].asInt)
                        addProperty("p1FirstAction", firstBySide["p1"])
                        addProperty("p2FirstAction", firstBySide["p2"])
                        addProperty("sameFirstAction", firstBySide["p1"] == firstBySide["p2"])
                        addProperty(
                            "decisionTraceSha256",
                            LocalBaselineCapture.digest(decisions.joinToString("\n").toByteArray(Charsets.UTF_8)),
                        )
                        addProperty("result", directory.relativize(gameDirectory.resolve("result.json")).toString())
                    })
                }
            }
        }

        val records = games.map { it.asJsonObject }
        val repeatGroups = records.groupBy { it["leadIndex"].asInt to it["seedIndex"].asInt }
        val comparableRepeatGroups = repeatGroups.filterValues { it.size > 1 }
        val stableGroups = comparableRepeatGroups.count { (_, group) ->
            group.map { listOf(it["status"], it["winner"], it["turn"], it["decisionTraceSha256"]) }.distinct().size == 1
        }
        val summary = JsonObject().apply {
            addProperty("schemaVersion", 1)
            addProperty("source", "WCoP 2025 SV OU Cow vs Wait2Seconds")
            addProperty("sourceUrl", "https://www.smogon.com/forums/threads/world-cup-of-pokemon-2025-sv-ou-discussion.3764281/")
            addProperty("teamUrl", "https://pokepast.es/8858c15c83e07353")
            addProperty("format", "SV OU singles mirror; same ordered entry on both sides; Tera unavailable to adapter")
            addProperty("seedsPerLead", seedsPerLead)
            addProperty("repeats", repeats)
            addProperty("games", records.size)
            addProperty("complete", records.count { it["status"].asString == "COMPLETE" })
            addProperty("turnLimited", records.count { it["status"].asString == "TURN_LIMIT" })
            addProperty("p1Wins", records.count { it["winner"]?.takeUnless { value -> value.isJsonNull }?.asString == "p1" })
            addProperty("p2Wins", records.count { it["winner"]?.takeUnless { value -> value.isJsonNull }?.asString == "p2" })
            addProperty("draws", records.count {
                it["status"].asString == "COMPLETE" && (it["winner"] == null || it["winner"].isJsonNull)
            })
            addProperty("sameFirstAction", records.count { it["sameFirstAction"].asBoolean })
            addProperty("repeatGroups", comparableRepeatGroups.size)
            addProperty("stableRepeatGroups", stableGroups)
            addProperty("evidence", "NATIVE_SHOWDOWN_ENGINE_LOCAL_BRAIN_PARTIAL_INPUT_ADAPTER_NOT_FULL_SV_OU")
            add("team", team(0))
            add("results", games)
        }
        Files.writeString(
            directory.resolve("summary.json"),
            GsonBuilder().setPrettyPrinting().create().toJson(summary),
            CREATE_NEW,
        )
        println(
            "TOURNAMENT_MIRROR games=${records.size} complete=${summary["complete"]} " +
                "p1=${summary["p1Wins"]} p2=${summary["p2Wins"]} draws=${summary["draws"]} " +
                "sameFirst=${summary["sameFirstAction"]} stableRepeats=$stableGroups/${comparableRepeatGroups.size}",
        )
        println("tournament mirror capture: $directory")
    }

    fun pair(leadIndex: Int, seedIndex: Int): JsonObject {
        require(leadIndex in 0 until TEAM_SIZE && seedIndex >= 0)
        val selected = team(leadIndex)
        return JsonObject().apply {
            addProperty("battleFormat", "SINGLE")
            add("battleSeed", JsonArray().apply {
                listOf(2026, 9, 23, leadIndex * 100 + seedIndex + 1).forEach(::add)
            })
            add("p1", selected.deepCopy())
            add("p2", selected.deepCopy())
        }
    }

    private fun team(leadIndex: Int): JsonObject {
        val rotated = SETS.drop(leadIndex) + SETS.take(leadIndex)
        return JsonObject().apply {
            add("setIds", JsonArray().apply { rotated.forEach { add(it["species"].asString) } })
            add("sets", JsonArray().apply { rotated.forEach { add(it.deepCopy()) } })
        }
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
        addProperty("gender", "M")
        addProperty("level", 100)
        add("moves", JsonArray().apply { moves.forEach(::add) })
        add("evs", stats(0, evs))
        add("ivs", stats(31, ivs))
    }

    private fun stats(default: Int, values: Map<String, Int>) = JsonObject().apply {
        STAT_IDS.forEach { addProperty(it, values[it] ?: default) }
    }

    private const val TEAM_SIZE = 6
    private val STAT_IDS = listOf("hp", "atk", "def", "spa", "spd", "spe")
    private val SETS = listOf(
        set(
            "Kingambit", "Heavy-Duty Boots", "Supreme Overlord", "Ghost", "Adamant",
            mapOf("atk" to 252, "def" to 4, "spe" to 252),
            listOf("Swords Dance", "Sucker Punch", "Kowtow Cleave", "Iron Head"),
        ),
        set(
            "Skarmory", "Rocky Helmet", "Sturdy", "Dragon", "Bold",
            mapOf("hp" to 252, "def" to 160, "spe" to 96),
            listOf("Body Press", "Iron Defense", "Roost", "Spikes"),
            mapOf("atk" to 0),
        ),
        set(
            "Garganacl", "Heavy-Duty Boots", "Purifying Salt", "Water", "Careful",
            mapOf("hp" to 252, "def" to 52, "spd" to 204),
            listOf("Stealth Rock", "Salt Cure", "Recover", "Protect"),
        ),
        set(
            "Gliscor", "Toxic Orb", "Poison Heal", "Normal", "Jolly",
            mapOf("hp" to 244, "def" to 12, "spe" to 252),
            listOf("Swords Dance", "Knock Off", "Facade", "Protect"),
        ),
        set(
            "Slowking-Galar", "Heavy-Duty Boots", "Regenerator", "Water", "Sassy",
            mapOf("hp" to 252, "def" to 16, "spd" to 240),
            listOf("Psychic Noise", "Chilly Reception", "Sludge Bomb", "Toxic"),
            mapOf("atk" to 0, "spe" to 0),
        ),
        set(
            "Dragapult", "Heavy-Duty Boots", "Infiltrator", "Ghost", "Naive",
            mapOf("atk" to 60, "spa" to 196, "spe" to 252),
            listOf("Dragon Darts", "Hex", "Will-O-Wisp", "U-turn"),
        ),
    )
}

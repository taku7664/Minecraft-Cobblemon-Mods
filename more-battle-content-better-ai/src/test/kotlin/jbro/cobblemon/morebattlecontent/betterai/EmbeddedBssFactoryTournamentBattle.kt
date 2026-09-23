package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import jbro.cobblemon.morebattlecontent.api.ai.BattleTrainerProfile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

/** Replays the exact generated entries and selections from BSS Champions League semifinal game 2. */
internal object EmbeddedBssFactoryTournamentBattle {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..3) { "Expected output directory, seeds and max turns" }
        val directory = Path.of(args[0]).toAbsolutePath().normalize()
        val seeds = args.getOrNull(1)?.toInt() ?: 4
        val maxTurns = args.getOrNull(2)?.toInt() ?: 200
        require(seeds in 1..20 && maxTurns in 1..1000)
        Files.createDirectories(directory.parent)
        Files.createDirectory(directory)

        EmbeddedPresetAudit.run(directory.resolve("audit"), teamPairs = 0)
        val engine = directory.resolve("audit/engine")
        val games = JsonArray()
        repeat(seeds) { seedIndex ->
            val gameDirectory = directory.resolve("seed-$seedIndex")
            val battlePair = pair(seedIndex)
            val result = EmbeddedTeamBattle.run(
                engine = engine,
                pair = battlePair,
                directory = gameDirectory,
                maxTurns = maxTurns,
                trainerProfile = BattleTrainerProfile.champion(),
            )
            games.add(JsonObject().apply {
                addProperty("seedIndex", seedIndex)
                add("battleSeed", battlePair["battleSeed"].deepCopy())
                addProperty("status", result["status"].asString)
                add("winner", result["winner"].deepCopy())
                addProperty("turn", result["turn"].asInt)
                addProperty("decisions", result["decisions"].asInt)
                addProperty("result", directory.relativize(gameDirectory.resolve("result.json")).toString())
            })
        }

        val records = games.map { it.asJsonObject }
        val summary = JsonObject().apply {
            addProperty("schemaVersion", 1)
            addProperty("tournament", "Battle Stadium Singles Champions League 2025 semifinals")
            addProperty("match", "purbaj vs IcyPenguin604, BSS Factory game 2")
            addProperty("sourceUrl", REPLAY_URL)
            addProperty("showdownCommit", SHOWDOWN_COMMIT)
            addProperty("reconstruction", "EXACT_FACTORY_ENTRIES_FROM_REPLAY_PLAYER_SEEDS_AND_SHOWDOWN_COMMIT")
            addProperty("selection", "EXACT_REPLAY_TEAM_INDICES_AND_LEAD_ORDER")
            addProperty("battleBoundary", "NEW_DAMAGE_ROLL_SEEDS;NO_TERA_CANDIDATES_IN_CURRENT_BETTER_AI_ADAPTER")
            addProperty("trainerProfile", "CHAMPION_SKILL_5_BOSS")
            addProperty("humanWinner", "p1")
            addProperty("humanTurns", 18)
            addProperty("humanP1Selection", "Thundurus-Therian,Umbreon,Urshifu-Rapid-Strike")
            addProperty("humanP2Selection", "Landorus-Therian,Rotom-Wash,Chi-Yu")
            add("p1Entry", p1Entry())
            add("p2Entry", p2Entry())
            add("results", games)
            addProperty("complete", records.count { it["status"].asString == "COMPLETE" })
            addProperty("p1Wins", records.count { it["winner"]?.takeUnless { value -> value.isJsonNull }?.asString == "p1" })
            addProperty("p2Wins", records.count { it["winner"]?.takeUnless { value -> value.isJsonNull }?.asString == "p2" })
        }
        Files.writeString(directory.resolve("summary.json"), GsonBuilder().setPrettyPrinting().create().toJson(summary), CREATE_NEW)
        println("BSS_FACTORY_TOURNAMENT games=${records.size} complete=${summary["complete"]} p1=${summary["p1Wins"]} p2=${summary["p2Wins"]}")
        println("BSS Factory tournament capture: $directory")
    }

    fun pair(seedIndex: Int): JsonObject {
        require(seedIndex >= 0)
        return JsonObject().apply {
            addProperty("battleFormat", "SINGLE")
            add("battleSeed", JsonArray().apply { listOf(2026, 9, 23, 20_001 + seedIndex).forEach(::add) })
            add("p1", selected(p1Entry(), listOf(4, 0, 3)))
            add("p2", selected(p2Entry(), listOf(1, 0, 2)))
        }
    }

    fun p1Entry(): JsonObject = team(P1_ENTRY)
    fun p2Entry(): JsonObject = team(P2_ENTRY)

    private fun selected(entry: JsonObject, indices: List<Int>): JsonObject {
        val sets = entry.getAsJsonArray("sets")
        return JsonObject().apply {
            add("setIds", JsonArray().apply { indices.forEach { add(sets[it].asJsonObject["species"].asString) } })
            add("sets", JsonArray().apply { indices.forEach { add(sets[it].deepCopy()) } })
        }
    }

    private fun team(json: String): JsonObject {
        val sets = JsonParser.parseString(json).asJsonArray
        return JsonObject().apply {
            add("setIds", JsonArray().apply { sets.forEach { add(it.asJsonObject["species"].asString) } })
            add("sets", sets)
        }
    }

    private const val REPLAY_URL = "https://replay.pokemonshowdown.com/gen9bssfactory-2505062470-sapdgsk0q5gva4dbjinyjfbv93ross5pw"
    private const val SHOWDOWN_COMMIT = "16c2c7e90bbfd5e04907c2daf0777367e9f80401"

    private val P1_ENTRY = """[
      {"species":"Umbreon","teraType":"Poison","gender":"M","item":"Leftovers","ability":"Inner Focus","level":50,"nature":"Bold","evs":{"hp":252,"def":252,"spd":4},"ivs":{},"moves":["Foul Play","Protect","Wish","Yawn"]},
      {"species":"Scizor","teraType":"Flying","gender":"M","item":"Sitrus Berry","ability":"Technician","level":50,"nature":"Adamant","evs":{"hp":252,"atk":28,"def":204,"spd":20,"spe":4},"ivs":{},"moves":["Swords Dance","Bullet Punch","U-turn","Knock Off"]},
      {"species":"Glimmora","teraType":"Grass","gender":"F","item":"Red Card","ability":"Toxic Debris","level":50,"nature":"Calm","evs":{"hp":252,"spd":124,"spe":132},"ivs":{"atk":0},"moves":["Power Gem","Energy Ball","Stealth Rock","Endure"]},
      {"species":"Urshifu-Rapid-Strike","teraType":"Poison","gender":"M","item":"Choice Band","ability":"Unseen Fist","level":50,"nature":"Jolly","evs":{"atk":252,"spd":4,"spe":252},"ivs":{},"moves":["Surging Strikes","Close Combat","U-turn","Aqua Jet"]},
      {"species":"Thundurus-Therian","teraType":"Water","gender":"M","item":"Choice Specs","ability":"Volt Absorb","level":50,"nature":"Timid","evs":{"hp":4,"spa":252,"spe":252},"ivs":{"atk":0},"moves":["Volt Switch","Thunderbolt","Tera Blast","Sludge Bomb"]},
      {"species":"Goodra-Hisui","teraType":"Flying","gender":"M","item":"Assault Vest","ability":"Sap Sipper","level":50,"nature":"Quiet","evs":{"hp":252,"spa":252,"spd":4},"ivs":{},"moves":["Flash Cannon","Ice Beam","Acid Spray","Earthquake"]}
    ]"""

    private val P2_ENTRY = """[
      {"species":"Rotom-Wash","teraType":"Steel","item":"Sitrus Berry","ability":"Levitate","level":50,"nature":"Bold","evs":{"hp":252,"def":252,"spa":4},"ivs":{"atk":0},"moves":["Hydro Pump","Volt Switch","Foul Play","Will-O-Wisp"]},
      {"species":"Landorus-Therian","teraType":"Fairy","gender":"M","item":"Leftovers","ability":"Intimidate","level":50,"nature":"Impish","evs":{"hp":252,"def":252,"spd":4},"ivs":{},"moves":["Stealth Rock","Earthquake","Rock Tomb","U-turn"]},
      {"species":"Chi-Yu","teraType":"Fire","item":"Choice Specs","ability":"Beads of Ruin","level":50,"nature":"Timid","evs":{"spa":252,"spd":4,"spe":252},"ivs":{"atk":0},"moves":["Overheat","Flamethrower","Dark Pulse","Psychic"]},
      {"species":"Kleavor","teraType":"Grass","gender":"M","item":"Focus Sash","ability":"Sharpness","level":50,"nature":"Jolly","evs":{"atk":252,"spd":4,"spe":252},"ivs":{},"moves":["Stone Axe","Night Slash","Trailblaze","X-Scissor"]},
      {"species":"Glastrier","teraType":"Ghost","item":"Assault Vest","ability":"Chilling Neigh","level":50,"nature":"Adamant","evs":{"hp":252,"atk":252,"spd":4},"ivs":{},"moves":["Icicle Crash","Heavy Slam","Close Combat","High Horsepower"]},
      {"species":"Orthworm","teraType":"Ghost","gender":"M","item":"Rocky Helmet","ability":"Earth Eater","level":50,"nature":"Impish","evs":{"hp":252,"def":252,"spd":4},"ivs":{},"moves":["Iron Defense","Body Press","Iron Head","Shed Tail"]}
    ]"""
}

package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import jbro.cobblemon.morebattlecontent.api.ai.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

/** Same-entry, seat-swapped difficulty checks using published BSS and VGC tournament entries. */
internal object EmbeddedTournamentDifficultyComparison {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..3) { "Expected output directory, seeds per matchup and max turns" }
        val directory = Path.of(args[0]).toAbsolutePath().normalize()
        val seedsPerMatchup = args.getOrNull(1)?.toInt() ?: 1
        val maxTurns = args.getOrNull(2)?.toInt() ?: 120
        require(seedsPerMatchup in 1..10 && maxTurns in 1..1000)
        Files.createDirectories(directory.parent)
        Files.createDirectory(directory)

        EmbeddedPresetAudit.run(directory.resolve("audit"), teamPairs = 0)
        val engine = directory.resolve("audit/engine")
        val games = JsonArray()
        FORMAT_FIXTURES.forEach { fixture ->
            ADJACENT_TIERS.forEachIndexed { matchupIndex, (lower, upper) ->
                repeat(seedsPerMatchup) { seedIndex ->
                    listOf(lower to upper, upper to lower).forEachIndexed { seatIndex, (p1Tier, p2Tier) ->
                        val gameId = "${fixture.id}-${lower.name.lowercase()}-vs-${upper.name.lowercase()}-seed-$seedIndex-seat-$seatIndex"
                        val gameDirectory = directory.resolve(gameId)
                        val pair = fixture.pair(matchupIndex, seedIndex)
                        val record = JsonObject().apply {
                            addProperty("gameId", gameId)
                            addProperty("format", fixture.format.name)
                            addProperty("p1Tier", p1Tier.name)
                            addProperty("p2Tier", p2Tier.name)
                            add("battleSeed", pair["battleSeed"].deepCopy())
                        }
                        try {
                            val result = EmbeddedTeamBattle.run(
                                engine,
                                pair,
                                gameDirectory,
                                maxTurns,
                                p1TrainerProfile = profile(p1Tier),
                                p2TrainerProfile = profile(p2Tier),
                            )
                            record.addProperty("status", result["status"].asString)
                            record.add("winner", result["winner"].deepCopy())
                            record.addProperty("turn", result["turn"].asInt)
                            record.addProperty("decisions", result["decisions"].asInt)
                            record.addProperty("illegalChoices", result["illegalChoices"].asInt)
                            record.addProperty("tauntSelections", selectedMoveCount(gameDirectory, "taunt"))
                            record.addProperty("result", directory.relativize(gameDirectory.resolve("result.json")).toString())
                        } catch (failure: Exception) {
                            record.addProperty("status", "ERROR")
                            record.addProperty("errorType", failure.javaClass.simpleName)
                            record.addProperty("error", failure.message.orEmpty().take(500))
                        }
                        games.add(record)
                    }
                }
            }
        }

        val records = games.map { it.asJsonObject }
        val summary = JsonObject().apply {
            addProperty("schemaVersion", 1)
            addProperty("method", "ADJACENT_DIFFICULTY_TIERS;SAME_SELECTED_ENTRY;SAME_SEED_SEAT_SWAP")
            addProperty("performanceBenchmark", false)
            addProperty("singlesRule", "BSS_BATTLE_PHASE_PICK_3_OF_6_LEVEL_50")
            addProperty("doublesRule", "VGC_BATTLE_PHASE_PICK_4_OF_6_LEVEL_50")
            addProperty("teraBoundary", "NO_TERA_CANDIDATES_IN_CURRENT_TEST_ADAPTER")
            addProperty("bssTournament", "Battle Stadium Singles Champions League 2025 semifinal, BSS Factory game 2")
            addProperty("bssSourceUrl", BSS_REPLAY_URL)
            addProperty("vgcTournament", "2024 Pokemon World Championships Masters champion Luca Ceribelli")
            addProperty("vgcTeamSourceUrl", VGC_TEAM_URL)
            addProperty("vgcReportUrl", VGC_REPORT_URL)
            addProperty("games", records.size)
            addProperty("complete", records.count { it["status"].asString == "COMPLETE" })
            addProperty("turnLimited", records.count { it["status"].asString == "TURN_LIMIT" })
            addProperty("errors", records.count { it["status"].asString == "ERROR" })
            add("winsByTier", JsonObject().apply {
                BattleTrainerTier.entries.forEach { tier ->
                    addProperty(tier.name, records.count { record -> winnerTier(record) == tier })
                }
            })
            add("entries", JsonObject().apply {
                add("singlesSelected", singlesTeam())
                add("doublesSelected", doublesTeam())
            })
            add("results", games)
        }
        Files.writeString(
            directory.resolve("summary.json"),
            GsonBuilder().setPrettyPrinting().create().toJson(summary),
            CREATE_NEW,
        )
        println(
            "TOURNAMENT_DIFFICULTY games=${summary["games"]} complete=${summary["complete"]} " +
                "limited=${summary["turnLimited"]} errors=${summary["errors"]} wins=${summary["winsByTier"]}",
        )
        println("Tournament difficulty comparison: $directory")
    }

    private fun winnerTier(record: JsonObject): BattleTrainerTier? {
        val winner = record["winner"]?.takeUnless { it.isJsonNull }?.asString ?: return null
        return BattleTrainerTier.valueOf(record[if (winner == "p1") "p1Tier" else "p2Tier"].asString)
    }

    private fun selectedMoveCount(directory: Path, moveId: String): Int {
        val trace = directory.resolve("decisions.jsonl")
        if (!Files.exists(trace)) return 0
        return Files.readAllLines(trace).count { line ->
            val decision = JsonParser.parseString(line).asJsonObject
            val selected = decision.getAsJsonObject("input").getAsJsonArray("actions")
                .map { it.asJsonObject }.singleOrNull { it["id"].asString == decision["actionId"].asString }
                ?: return@count false
            actionMoves(selected).any { canonical(it) == canonical(moveId) }
        }
    }

    private fun actionMoves(action: JsonObject): Sequence<String> = when (action["kind"].asString) {
        "move" -> sequenceOf(action["moveId"].asString)
        "composite" -> action.getAsJsonArray("components").asSequence().map { it.asJsonObject }
            .flatMap(::actionMoves)
        else -> emptySequence()
    }

    private fun profile(tier: BattleTrainerTier): BattleTrainerProfile = BattleTrainerProfile.balanced(
        skillLevel = when (tier) {
            BattleTrainerTier.INTRODUCTORY -> 1
            BattleTrainerTier.STANDARD -> 2
            BattleTrainerTier.ADVANCED -> 4
            BattleTrainerTier.BOSS -> 5
        },
        difficulty = BattleDifficultyProfiles.forTier(tier),
    )

    private fun singlesPair(seedGroup: Int, seedIndex: Int) = mirrorPair(
        BattleFormat.SINGLE,
        singlesTeam(),
        listOf(2026, 9, 24, 10_000 + seedGroup * 100 + seedIndex),
    )

    private fun doublesPair(seedGroup: Int, seedIndex: Int) = mirrorPair(
        BattleFormat.DOUBLE,
        doublesTeam(),
        listOf(2026, 9, 24, 20_000 + seedGroup * 100 + seedIndex),
    )

    private fun mirrorPair(format: BattleFormat, team: JsonObject, seed: List<Int>) = JsonObject().apply {
        addProperty("battleFormat", format.name)
        add("battleSeed", JsonArray().apply { seed.forEach(::add) })
        add("p1", team.deepCopy())
        add("p2", team.deepCopy())
    }

    /** Exact selected three from purbaj's replay entry, mirrored to isolate difficulty. */
    private fun singlesTeam(): JsonObject {
        val entry = EmbeddedBssFactoryTournamentBattle.p1Entry().getAsJsonArray("sets")
        return team(listOf(entry[4].asJsonObject, entry[0].asJsonObject, entry[3].asJsonObject))
    }

    /** Published Worlds-winning sets; fixed four only because team-preview selection AI is out of scope. */
    private fun doublesTeam(): JsonObject = team(JsonParser.parseString(VGC_SELECTED).asJsonArray.map { it.asJsonObject })

    private fun team(sets: List<JsonObject>) = JsonObject().apply {
        add("setIds", JsonArray().apply { sets.forEach { add(it["species"].asString) } })
        add("sets", JsonArray().apply { sets.forEach { add(it.deepCopy()) } })
    }

    private fun canonical(value: String): String =
        value.substringAfter(':').lowercase(java.util.Locale.ROOT).filter(Char::isLetterOrDigit)

    private data class Fixture(
        val id: String,
        val format: BattleFormat,
        val pair: (Int, Int) -> JsonObject,
    )

    private val FORMAT_FIXTURES = listOf(
        Fixture("bss-3v3", BattleFormat.SINGLE, ::singlesPair),
        Fixture("vgc-4v4", BattleFormat.DOUBLE, ::doublesPair),
    )
    private val ADJACENT_TIERS = listOf(
        BattleTrainerTier.INTRODUCTORY to BattleTrainerTier.STANDARD,
        BattleTrainerTier.STANDARD to BattleTrainerTier.ADVANCED,
        BattleTrainerTier.ADVANCED to BattleTrainerTier.BOSS,
    )

    private const val BSS_REPLAY_URL =
        "https://replay.pokemonshowdown.com/gen9bssfactory-2505062470-sapdgsk0q5gva4dbjinyjfbv93ross5pw"
    private const val VGC_TEAM_URL = "https://limitlessvgc.com/teams/2196"
    private const val VGC_REPORT_URL = "https://victoryroad.pro/2024/09/22/luca-ceribelli-worlds-report/"
    private val VGC_SELECTED = """[
      {"species":"Miraidon","teraType":"Fairy","item":"Choice Specs","ability":"Hadron Engine","level":50,"nature":"Modest","evs":{"hp":44,"atk":0,"def":4,"spa":244,"spd":12,"spe":204},"ivs":{"atk":0},"moves":["Electro Drift","Draco Meteor","Dazzling Gleam","Volt Switch"]},
      {"species":"Ogerpon-Hearthflame","teraType":"Fire","item":"Hearthflame Mask","ability":"Mold Breaker","level":50,"nature":"Adamant","evs":{"hp":188,"atk":76,"def":52,"spa":0,"spd":4,"spe":188},"ivs":{},"moves":["Spiky Shield","Ivy Cudgel","Wood Hammer","Follow Me"]},
      {"species":"Urshifu-Rapid-Strike","teraType":"Stellar","item":"Focus Sash","ability":"Unseen Fist","level":50,"nature":"Adamant","evs":{"hp":0,"atk":252,"def":0,"spa":0,"spd":4,"spe":252},"ivs":{},"moves":["Protect","Surging Strikes","Close Combat","Aqua Jet"]},
      {"species":"Farigiraf","teraType":"Fairy","item":"Electric Seed","ability":"Armor Tail","level":50,"nature":"Bold","evs":{"hp":204,"atk":0,"def":164,"spa":4,"spd":108,"spe":28},"ivs":{"atk":6},"moves":["Psychic Noise","Foul Play","Helping Hand","Trick Room"]}
    ]"""
}

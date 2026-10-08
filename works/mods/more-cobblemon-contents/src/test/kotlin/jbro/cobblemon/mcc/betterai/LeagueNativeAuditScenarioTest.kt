package jbro.cobblemon.mcc.betterai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import jbro.cobblemon.mcc.betterai.engine.RefSet
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * Plays the League Challenge's own teams with the inputs the game hands the trainer (the opposing team preview and
 * its own exact sets), so the native search runs as it does in the game, and counts why it did not finish.
 * Each challenge team plays at its trainer's AI skill against another 6-member League team at skill 4.
 * Report: build/reports/league-native-audit.md, traces in build/reports/league-native-audit-runs/.
 *
 *     ./gradlew :more-cobblemon-contents:unitTest -Pscope=ai -Ptests=LeagueNativeAudit -Poracle
 *
 * LEAGUE_AUDIT_TEAMS limits the challenge teams, LEAGUE_AUDIT_SEEDS sets battles per team (default 1).
 * The bridge offers no gimmicks, so Mega Evolution is never chosen; forms are fixed Showdown formes.
 */
@EnabledIfSystemProperty(named = "betterai.oracle", matches = "true")
class LeagueNativeAuditScenarioTest {
    private val leagueRoot = Path.of("../more-cobblemon-contents-league-challenge/src/main/resources/data/" +
        "more_cobblemon_contents_league_challenge/league-challenge")

    private fun json(path: Path) = JsonParser.parseString(Files.readString(path)).asJsonObject

    private fun canonical(value: String) = value.lowercase().filter(Char::isLetterOrDigit)

    private fun refSet(properties: String): RefSet {
        val tokens = properties.trim().split(Regex("\\s+"))
        val values = tokens.drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }
        val stat = mapOf("hp" to "hp", "attack" to "atk", "defence" to "def", "special_attack" to "spa",
            "special_defence" to "spd", "speed" to "spe")
        fun spread(suffix: String) = stat.mapNotNull { (key, id) -> values["${key}_$suffix"]?.let { id to it.toInt() } }.toMap()
        val form = values["form"]
        return RefSet(
            species = if (form == null) tokens[0] else "${tokens[0]}-$form",
            moves = values.getValue("moves").split(','),
            ability = values["ability"].orEmpty(),
            item = values["held_item"]?.let { canonical(it.substringAfter(':')) }.orEmpty(),
            level = values.getValue("level").toInt(),
            nature = values["nature"] ?: "serious",
            evs = spread("ev"),
            ivs = spread("iv"),
            gender = when (values["gender"]) { "male" -> "M"; "female" -> "F"; else -> "" },
        )
    }

    private fun team(id: String) = json(leagueRoot.resolve("teams/$id.json")).getAsJsonArray("pokemon").map { refSet(it.asString) }

    private fun side(sets: List<RefSet>, side: String) = JsonObject().apply {
        add("setIds", JsonArray().apply { sets.forEach { add(it.species) } })
        add("sets", JsonArray().apply { sets.forEachIndexed { index, set -> add(set.toJson(side, index)) } })
    }

    @Test
    fun `league battles run the native search with the game's preview and own sets`() {
        assumeTrue(System.getenv("LEAGUE_DUEL") == null, "a duel run skips the audit")
        val challenges = Files.list(leagueRoot.resolve("challenges")).use { it.sorted().toList() }
            .map { json(it) }.filter { it["format"].asString == "SINGLE" }
        val seeds = System.getenv("LEAGUE_AUDIT_SEEDS")?.toInt() ?: 1
        val trainers = challenges.map { challenge ->
            val trainer = json(leagueRoot.resolve("trainers/${challenge["trainer"].asString.substringAfter(':')}.json"))
            Triple(trainer["team"].asString.substringAfter(':'), trainer["ai_skill"].asInt, challenge["trainer"].asString)
        }
        val players = trainers.map { it.first }.distinct().filter { team(it).size == 6 }
        val opponents = trainers.take(System.getenv("LEAGUE_AUDIT_TEAMS")?.toInt() ?: Int.MAX_VALUE)
        val root = Path.of("build/reports/league-native-audit-runs/${UUID.randomUUID()}")
        Files.createDirectories(root.parent)
        EmbeddedPresetAudit.run(root.resolve("audit"), teamPairs = 0)
        val engine = root.resolve("audit/engine")
        val executor = Executors.newFixedThreadPool(System.getenv("LEAGUE_AUDIT_THREADS")?.toInt() ?: 6) { task ->
            Thread(null, task, "league-native-audit", 256L shl 20).apply { isDaemon = true }
        }
        val futures = opponents.flatMapIndexed { index, (teamId, skill, label) ->
            (0 until seeds).map { seed ->
                val playerTeam = players.filter { it != teamId }[(index + seed) % (players.size - 1)]
                executor.submit<String> {
                    val random = Random(20261008L * 31 + index * 100L + seed)
                    val pair = JsonObject().apply {
                        addProperty("battleFormat", "SINGLE")
                        addProperty("teamPreview", true)
                        add("battleSeed", JsonArray().apply { repeat(4) { add(random.nextInt(65536)) } })
                        add("p1", side(team(playerTeam), "p1"))
                        add("p2", side(team(teamId), "p2"))
                    }
                    val directory = root.resolve("$teamId-vs-$playerTeam-$seed")
                    try {
                        val result = EmbeddedTeamBattle.run(engine, pair, directory, maxTurns = 100,
                            p1TrainerProfile = BattleTrainerProfile.balanced(4),
                            p2TrainerProfile = BattleTrainerProfile.balanced(skill))
                        "$label ${result["status"].asString}"
                    } catch (failure: Throwable) {
                        System.err.println("LEAGUE_NATIVE_AUDIT_ERROR $label ${failure.stackTraceToString().take(3000)}")
                        "$label ERROR ${failure.javaClass.simpleName}: ${failure.message?.take(200)}"
                    }
                }
            }
        }
        val outcomes = futures.map { it.get(60, TimeUnit.MINUTES) }
        executor.shutdown()

        // Every decision's native outcome, keyed by what stopped it.
        val counts = sortedMapOf<String, Int>()
        val examples = linkedMapOf<String, String>()
        Files.list(root).use { it.toList() }.filter { Files.exists(it.resolve("decisions.jsonl")) }.forEach { battle ->
            Files.readAllLines(battle.resolve("decisions.jsonl")).forEach { line ->
                val decision = JsonParser.parseString(line).asJsonObject
                val native = decision.getAsJsonObject("native") ?: return@forEach
                val key = listOf("status", "rebuilt", "planIssues", "reconciliation", "search", "failedRun")
                    .mapNotNull { name -> native[name]?.asString?.takeIf(String::isNotEmpty)?.let { "$name=$it" } }
                    .plus(listOfNotNull(native["detail"]?.asString?.split(',')?.firstOrNull { it.startsWith("rebuild:") }
                        ?.substringBefore('@')))
                    .joinToString(" ")
                counts.merge(key, 1, Int::plus)
                examples.putIfAbsent(key, "${battle.fileName} ${decision["side"].asString} turn=${decision["turn"].asInt} " +
                    (native["detail"]?.asString ?: ""))
            }
        }
        val report = StringBuilder("# League native search audit\n\n")
        report.append("| native outcome | decisions | example |\n|---|---|---|\n")
        counts.entries.sortedByDescending { it.value }.forEach { (key, count) ->
            report.append("| $key | $count | ${examples[key]?.replace("|", "/")?.take(300)} |\n")
        }
        report.append("\n## Battles\n\n")
        outcomes.forEach { report.append("- $it\n") }
        Files.writeString(Path.of("build/reports/league-native-audit.md"), report)
        println(report)
    }

    /**
     * Native sessions that correct an opponent's hidden stats from observed damage against sessions that drop the
     * worlds instead, both at skill 4 on the League's 6-member teams. Each pairing plays both team orders from both
     * seats, so neither the teams nor the seat decide. Report: build/reports/league-stat-fit-duel.md.
     *
     *     LEAGUE_DUEL=stat-fit ./gradlew :more-cobblemon-contents:unitTest -Pscope=ai -Ptests=LeagueNativeAudit -Poracle
     *
     * LEAGUE_DUEL_PAIRS sets how many team pairings play (default 12, four games each).
     */
    @Test
    fun `league duel measures hidden stat fitting`() {
        assumeTrue(System.getenv("LEAGUE_DUEL") == "stat-fit", "opt-in with LEAGUE_DUEL=stat-fit")
        val challenges = Files.list(leagueRoot.resolve("challenges")).use { it.sorted().toList() }
            .map { json(it) }.filter { it["format"].asString == "SINGLE" }
        val players = challenges.map { challenge ->
            json(leagueRoot.resolve("trainers/${challenge["trainer"].asString.substringAfter(':')}.json"))["team"].asString
                .substringAfter(':')
        }.distinct().filter { team(it).size == 6 }
        val pairs = players.indices.map { players[it] to players[(it + 1) % players.size] }
            .take(System.getenv("LEAGUE_DUEL_PAIRS")?.toInt() ?: 12)
        val root = Path.of("build/reports/league-stat-fit-duel-runs/${UUID.randomUUID()}")
        Files.createDirectories(root.parent)
        EmbeddedPresetAudit.run(root.resolve("audit"), teamPairs = 0)
        val engine = root.resolve("audit/engine")
        val executor = Executors.newFixedThreadPool(System.getenv("LEAGUE_AUDIT_THREADS")?.toInt() ?: 6) { task ->
            Thread(null, task, "league-stat-fit-duel", 256L shl 20).apply { isDaemon = true }
        }
        val futures = pairs.flatMapIndexed { index, (first, second) ->
            EmbeddedPolicyComparison.schedule(index).mapIndexed { game, assignment ->
                executor.submit<String> {
                    val random = Random(20261008L * 37 + index * 10L)
                    val pair = JsonObject().apply {
                        addProperty("battleFormat", "SINGLE")
                        addProperty("teamPreview", true)
                        add("battleSeed", JsonArray().apply { repeat(4) { add(random.nextInt(65536)) } })
                        add("p1", side(team(first), "p1"))
                        add("p2", side(team(second), "p2"))
                    }
                    val fitting = if (assignment.challengerP1) "p1" else "p2"
                    val label = "$first-vs-$second game=$game reverse=${assignment.reverseTeams} fitting=$fitting"
                    try {
                        val result = EmbeddedTeamBattle.run(engine, EmbeddedNativePairs.orient(pair, assignment.reverseTeams),
                            root.resolve("$first-vs-$second-$game"), maxTurns = 100,
                            trainerProfile = BattleTrainerProfile.balanced(4),
                            withoutStatFitting = setOf(if (fitting == "p1") "p2" else "p1"))
                        "$label ${EmbeddedNativePairs.outcome(result, reverse = !assignment.challengerP1)}"
                    } catch (failure: Throwable) {
                        System.err.println("LEAGUE_DUEL_ERROR $label ${failure.stackTraceToString().take(3000)}")
                        "$label ERROR"
                    }
                }
            }
        }
        val outcomes = futures.map { it.get(60, TimeUnit.MINUTES) }
        executor.shutdown()
        val tally = outcomes.groupingBy { it.substringAfterLast(' ') }.eachCount()
        val report = StringBuilder("# League duel: hidden stat fitting on against off\n\n")
        report.append("Outcome for the side that fits: $tally\n\n")
        outcomes.forEach { report.append("- $it\n") }
        Files.writeString(Path.of("build/reports/league-stat-fit-duel.md"), report)
        println(report)
    }
}

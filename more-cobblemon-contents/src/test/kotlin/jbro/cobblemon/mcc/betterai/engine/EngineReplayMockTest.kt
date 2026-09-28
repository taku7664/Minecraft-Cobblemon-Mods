package jbro.cobblemon.mcc.betterai.engine

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import jbro.cobblemon.mcc.betterai.engine.sim.Battle
import jbro.cobblemon.mcc.betterai.engine.sim.BattleOptions
import jbro.cobblemon.mcc.betterai.engine.sim.Side
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

/**
 * Mock replays of real battles with open team sheets. The replay gives the teams (not the EVs, which come
 * from the usage data) and every choice the players made; the engine plays those choices under many seeds
 * and the report shows, turn by turn, how often the engine reproduces what happened and whether the real
 * HP values fall inside the engine's range.
 */
class EngineReplayMockTest {
    private val dex = EngineReferee.dex
    private val replays = listOf("gen9vgc2025regjbo3-2512943310" to "gen9vgc2025regj")

    /** What one slot did in one turn of the real battle. */
    private data class Act(val switchTo: String? = null, val move: String? = null, val target: String? = null, val tera: Boolean = false)

    private data class Replay(
        val id: String,
        val gameType: String,
        /** The team sheets in the order the Pokemon appeared, without EVs or nature. */
        val sheets: Map<String, List<RefSet>>,
        /** "p1:Incineroar" to "careful:252/4/0/0/252/0", the spread each Pokemon plays with. */
        val spreads: Map<String, String>,
        /** The most used spreads for each Pokemon, the ones a fit tries. */
        val candidates: Map<String, List<String>>,
        /** Turn number to slot ("p1a") to what that slot did. */
        val turns: Map<Int, Map<String, Act>>,
        /** Pokemon each side sent in when asked mid-turn or after a faint, in order. */
        val replacements: Map<String, List<String>>,
        /** The real protocol lines from `|start|`, with Pokemon named by species. */
        val lines: List<String>,
    ) {
        val teams: Map<String, List<RefSet>> by lazy {
            sheets.mapValues { (side, team) -> team.map { set -> spread(set, spreads["$side:${set.species}"]) } }
        }
    }

    private companion object Spreads {
        fun spread(set: RefSet, spread: String?): RefSet {
            val (nature, evs) = spread?.split(":")?.let { (n, e) -> n to RefSet.STATS.zip(e.split("/").map { it.toInt() }).toMap() } ?: return set
            return set.copy(nature = nature, evs = evs)
        }

        const val CANDIDATES = 12
        // Enough for the report's rates; the AI review finds its seed separately.
        const val MOCK_SEEDS = 500
        const val FIT_SEEDS = 200
        val IDENT = Regex("p[12][a-d]?: .+")
        val HP = Regex("(?<=\\|)(\\d+)(?=/100)")
        val SKIPPED = setOf("", "t:", "j", "l", "c", "raw", "chat", "inactive", "inactiveoff", "html", "uhtml", "debug", "split", "request", "start", "upkeep", "pp_update", "player")
    }

    private fun parse(id: String, format: String): Replay {
        val raw = requireNotNull(javaClass.getResourceAsStream("/ai-engine/replays/$id.log")).reader().use { it.readText() }.lines()
        val usage = JsonParser.parseString(requireNotNull(javaClass.getResourceAsStream(
            "/data/more_cobblemon_contents/opponent_build_usage/$format-2025-12-1500.json")).reader().use { it.readText() })
            .asJsonObject.getAsJsonObject("species")
        val players = HashMap<String, String>()
        val sheets = HashMap<String, Map<String, RefSet>>()
        val names = HashMap<String, String>() // "p1: Ogerpon" -> "Ogerpon-Hearthflame"
        val order = HashMap<String, MutableList<String>>()
        val turns = HashMap<Int, MutableMap<String, Act>>()
        val replacements = HashMap<String, MutableList<String>>()
        val lines = ArrayList<String>()
        var gameType = "singles"
        var turn = 0
        var upkeep = false
        var started = false
        val tera = HashSet<String>()
        for (line in raw) {
            val parts = line.split("|")
            if (parts.size < 2) continue
            when (parts[1]) {
                "player" -> if (parts.size > 3 && parts[3].isNotEmpty()) players[parts[3]] = parts[2]
                "gametype" -> gameType = parts[2]
                "showteam" -> sheets[parts[2]] = parts.drop(3).joinToString("|").split("]").associate { packed ->
                    sheet(packed).let { it.species to it }
                }
                "start" -> started = true
                "turn" -> { turn = parts[2].toInt(); upkeep = false; tera.clear() }
                "upkeep" -> upkeep = true
                "switch", "drag" -> {
                    val side = parts[2].take(2)
                    val species = parts[3].substringBefore(",")
                    names["$side: " + parts[2].substringAfter(": ")] = species
                    order.getOrPut(side) { ArrayList() }.let { if (species !in it) it += species }
                    when {
                        turn == 0 -> Unit
                        upkeep || parts.any { it.startsWith("[from]") } -> replacements.getOrPut(side) { ArrayList() } += species
                        else -> turns.getOrPut(turn) { HashMap() }.putIfAbsent(parts[2].take(3), Act(switchTo = species))
                    }
                }
                "-terastallize" -> tera += parts[2].take(3)
                "move" -> if (turn > 0 && parts.none { it.startsWith("[from]") }) {
                    val slot = parts[2].take(3)
                    val target = parts.getOrNull(4)?.takeIf { it.matches(Regex("p[12][a-d]: .*")) }?.take(3)
                    turns.getOrPut(turn) { HashMap() }.putIfAbsent(slot, Act(move = Js.toID(parts[3]), target = target, tera = slot in tera))
                }
            }
            if (started) lines += line
        }
        val teams = order.mapValues { (side, species) -> species.map { requireNotNull(sheets[side]?.get(it)) { "$side $it not on the team sheet" } } }
        val candidates = teams.flatMap { (side, team) ->
            team.map { set ->
                "$side:${set.species}" to (usage.getAsJsonObject(Js.toID(set.species))?.getAsJsonObject("spreads")?.entrySet()
                    ?.sortedByDescending { it.value.asDouble }?.take(CANDIDATES)?.map { it.key } ?: emptyList())
            }
        }.toMap()
        // Spreads fitted to this battle's HP lines (see the fit test) win over the most used ones.
        val fitted = javaClass.getResourceAsStream("/ai-engine/replays/$id.spreads.json")?.reader()?.use { r ->
            JsonParser.parseReader(r).asJsonObject.entrySet().associate { it.key to it.value.asString }
        } ?: emptyMap()
        val spreads = candidates.mapValues { (key, list) -> fitted[key] ?: list.firstOrNull() ?: "serious:0/0/0/0/0/0" }
        val canonical = lines.mapNotNull { normalize(it, { ident -> names[ident.take(2) + ": " + ident.substringAfter(": ")] }, players) }
        return Replay(id, gameType, teams, spreads, candidates, turns, replacements, canonical)
    }

    /** One member of Showdown's packed team format. Open team sheets leave the EVs and nature out. */
    private fun sheet(packed: String): RefSet {
        val f = packed.split("|")
        val species = requireNotNull(dex.species(Js.toID(f[1].ifEmpty { f[0] }))) { "unknown species ${f[0]}" }
        val extra = f.getOrNull(11)?.split(",") ?: emptyList()
        return RefSet(species.name, f[4].split(",").map { Js.toID(it) }, ability = Js.toID(f[3]), item = Js.toID(f[2]),
            level = f.getOrNull(10)?.toIntOrNull() ?: 100,
            gender = f[7].ifEmpty { species.gender.ifEmpty { "M" } }, teraType = extra.getOrNull(5)?.ifEmpty { null })
    }

    /**
     * A protocol line both logs can be compared on: Pokemon named "p1a: Species", switch details dropped,
     * players named by side, and lines that carry no battle event removed.
     */
    private fun normalize(line: String, species: (String) -> String?, players: Map<String, String>): String? {
        val parts = line.split("|")
        if (parts.size < 2 || parts[1] in SKIPPED || line.startsWith("||")) return null
        val out = parts.drop(1).mapIndexed { i, part ->
            when {
                parts[1] in setOf("switch", "drag", "replace") && i == 2 -> null
                parts[1] == "win" && i == 1 -> players[part] ?: part
                part.matches(IDENT) -> part.substringBefore(":").let { pos -> "$pos: " + (species(part) ?: part.substringAfter(": ")) }
                part.startsWith("[of] ") && part.drop(5).matches(IDENT) -> "[of] " + part.drop(5).substringBefore(":") + ": " +
                    (species(part.drop(5)) ?: part.substringAfter(": "))
                else -> part
            }
        }.filterNotNull()
        // Cobblemon's Showdown names 4x and 1/4x hits apart; the official server logs them as plain super effective and resisted.
        val kind = when (out[0]) {
            "-extremelyeffective" -> "-supereffective"
            "-mostlyineffective" -> "-resisted"
            else -> out[0]
        }
        return "|" + (listOf(kind) + out.drop(1)).joinToString("|")
    }

    /** The line with HP numbers masked, so seeds are compared on what happened rather than on damage rolls. */
    private fun shape(line: String): String {
        // A spread move logs one of its targets at random, so the shape drops it.
        val masked = line.replace(HP, "#")
        if (!masked.startsWith("|move|") || "|[spread]" !in masked) return masked
        return masked.split("|").let { p -> (p.take(4) + "" + p.drop(5)).joinToString("|") }
    }

    private fun hp(line: String): Int? = HP.find(line)?.groupValues?.get(1)?.toInt()

    private class Run(val lines: List<String>, val choices: List<Pair<String, String>>, val stoppedAt: String?, val winner: String?,
                      val seed: IntArray)

    /** Plays the real choices on the engine under one seed until the battle ends or no longer fits them. */
    private fun play(replay: Replay, seed: IntArray): Run {
        val battle = Battle(dex, BattleOptions(gameType = replay.gameType, seed = seed))
        val p1 = replay.teams["p1"].orEmpty()
        val p2 = replay.teams["p2"].orEmpty()
        battle.setPlayer("p1", "p1", p1.mapIndexed { i, s -> s.toSet("p1", i) })
        battle.setPlayer("p2", "p2", p2.mapIndexed { i, s -> s.toSet("p2", i) })
        val species = HashMap<String, String>()
        p1.forEachIndexed { i, s -> species[s.uuid("p1", i)] = s.species }
        p2.forEachIndexed { i, s -> species[s.uuid("p2", i)] = s.species }
        val queues = replay.replacements.mapValues { ArrayDeque(it.value) }
        val choices = ArrayList<Pair<String, String>>()
        var stopped: String? = null
        var steps = 0
        while (!battle.ended && stopped == null && steps++ < 200) {
            val made = battle.sides.map { side ->
                val choice = decide(battle, side, replay, queues[side.id]) ?: run {
                    stopped = "turn ${battle.turn} ${side.id}: the real choice does not fit (${side.requestState} request)"
                    return@map ""
                }
                if (choice.isEmpty()) return@map ""
                if (!runCatching { side.choose(choice) }.getOrDefault(false)) {
                    stopped = "turn ${battle.turn} ${side.id}: '$choice' rejected (${side.choice.error})"
                }
                choice
            }
            if (stopped != null) break
            choices += made[0] to made[1]
            battle.commitDecisions()
        }
        val lines = publicLines(battle.log).mapNotNull { normalize(it, { ident -> species[ident.substringAfter(": ")] }, emptyMap()) }
        return Run(lines, choices, stopped, battle.winner.takeIf { battle.ended }, seed)
    }

    /** Showdown writes a private and a public copy of HP lines; keep the public one, as a replay shows. */
    private fun publicLines(log: List<String>): List<String> {
        val out = ArrayList<String>()
        val start = log.indexOfFirst { it == "|start" }.coerceAtLeast(0)
        var i = start
        while (i < log.size) {
            if (log[i].startsWith("|split|")) { out += log[i + 2]; i += 3 } else { out += log[i]; i++ }
        }
        return out
    }

    /** The real player's choice for this request, or null when the battle went a way the replay did not. */
    private fun decide(battle: Battle, side: Side, replay: Replay, queue: ArrayDeque<String>?): String? {
        val request = side.activeRequest ?: return ""
        if (request.wait) return ""
        val used = HashSet<Int>()
        fun switchTo(species: String): String? {
            val index = side.pokemon.indices.firstOrNull { i ->
                val p = side.pokemon[i]
                p.species.name == species && !p.fainted && !p.isActive && i !in used
            } ?: return null
            used += index
            return "switch ${index + 1}"
        }
        request.forceSwitch?.let { table ->
            val slots = ArrayList<String>()
            for (needs in table) {
                slots += if (!needs) "pass" else switchTo(queue?.removeFirstOrNull() ?: return null) ?: return null
            }
            return slots.joinToString(", ")
        }
        // The real battle ended before this turn, so there is no real choice to play.
        val acts = replay.turns[battle.turn] ?: return null
        return side.active.mapIndexed { slot, pokemon ->
            val data = request.active?.getOrNull(slot)
            if (pokemon == null || pokemon.fainted || data == null) return@mapIndexed "pass"
            val act = acts[side.id + ('a' + slot)]
            act?.switchTo?.let { return@mapIndexed switchTo(it) ?: return null }
            // A move the replay never shows (the Pokemon flinched or fainted first): a usable move without
            // priority, so it does not change the turn order the replay shows.
            val usable = data.moves.indices.filter { !Js.truthy(data.moves[it].disabled) }
            val index = act?.move?.let { id -> data.moves.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return null }
                ?: usable.firstOrNull { (dex.move(data.moves[it].id)?.priority ?: 0) == 0 } ?: usable.firstOrNull() ?: 0
            val move = data.moves[index]
            var choice = "move ${index + 1}"
            if (side.active.size > 1 && battle.actions.targetTypeChoices(move.target)) {
                val loc = act?.target?.let { t ->
                    val position = t[2] - 'a' + 1
                    if (t.take(2) == side.id) -position else position
                } ?: listOf(1, 2, -1, -2).firstOrNull { battle.validTargetLoc(it, pokemon, move.target) && pokemon.getAtLoc(it)?.fainted == false }
                if (loc != null) choice += " $loc"
            }
            if (act?.tera == true) choice += " terastallize"
            choice
        }.joinToString(", ")
    }

    /** One HP line of the real battle and what the seeds that got that far showed there. */
    private class Row(val line: String, val real: Int, val values: List<Int>) {
        val outside: Int get() = if (values.isEmpty()) 0 else maxOf(0, values.first() - real, real - values.last())
    }

    private class Stats(val runs: List<Run>, val reach: List<Int>, val rows: List<Row>)

    private fun stats(replay: Replay, seeds: Int): Stats {
        val random = Random(20251215)
        val runs = (0 until seeds).map { play(replay, IntArray(4) { random.nextInt(65536) }) }
        val real = replay.lines
        val realShapes = real.map { shape(it) }
        // How far each seed follows the real battle before something different happens.
        val reach = runs.map { run -> (real.indices).firstOrNull { run.lines.getOrNull(it)?.let(::shape) != realShapes[it] } ?: real.size }
        val rows = real.withIndex().mapNotNull { (index, line) ->
            val value = hp(line)?.takeIf { line.startsWith("|-damage|") || line.startsWith("|-heal|") } ?: return@mapNotNull null
            Row(line, value, runs.indices.filter { reach[it] > index }.mapNotNull { hp(runs[it].lines[index]) }.sorted())
        }
        return Stats(runs, reach, rows)
    }

    /**
     * How badly a set of spreads explains the real battle: HP values outside the engine's roll range weigh
     * most, lines no seed reached count as far off, and the distance from the median breaks ties.
     */
    private fun misfit(stats: Stats): Double = stats.rows.sumOf { row ->
        if (row.values.isEmpty()) 400.0
        else row.outside.let { it * it * 4.0 } + (row.real - row.values[row.values.size / 2]).let { it * it * 0.1 }
    }

    private fun mock(replay: Replay, seeds: Int): String {
        val stats = stats(replay, seeds)
        val runs = stats.runs
        val reach = stats.reach
        val real = replay.lines
        val outcomeKinds = setOf("move", "switch", "drag", "faint", "cant", "-fail", "-terastallize", "turn", "win")
        fun outcome(lines: List<String>) = lines.filter { it.split("|").getOrNull(1) in outcomeKinds }.map { shape(it) }
        val realOutcome = outcome(real)
        val sameOutcome = runs.count { outcome(it.lines) == realOutcome }
        val realWinner = real.lastOrNull { it.startsWith("|win|") }?.split("|")?.getOrNull(2)
        return buildString {
            appendLine("# ${replay.id} 모의 재현")
            appendLine()
            val fitted = this@EngineReplayMockTest.javaClass.getResource("/ai-engine/replays/${replay.id}.spreads.json") != null
            appendLine("- 시드 $seeds 개로 실제 선택을 그대로 재생했다. 노력치와 성격은 " +
                (if (fitted) "사용률 상위 배분 중에서 이 경기의 HP 기록에 가장 잘 맞는 것을 골랐다." else "사용률 1위 배분이다."))
            replay.teams.forEach { (side, team) ->
                appendLine("- $side: " + team.joinToString(", ") { "${it.species} (${replay.spreads["$side:${it.species}"]})" })
            }
            appendLine("- HP 기록 ${stats.rows.size}줄 중 엔진 범위 밖: ${stats.rows.count { it.outside > 0 }}줄, " +
                "그 줄까지 간 시드가 없는 줄: ${stats.rows.count { it.values.isEmpty() }}줄")
            appendLine("- 로그 전체(피해량 제외)가 실제와 같은 시드: ${reach.count { it == real.size }} / $seeds")
            appendLine("- 행동 순서, 기절, 교체, 승패가 실제와 같은 시드(급소와 피해량 무시): $sameOutcome / $seeds")
            val lastTurn = replay.turns.keys.max()
            val through = runs.filter { it.stoppedAt == null || it.stoppedAt.startsWith("turn ${lastTurn + 1} ") }
            appendLine("- 실제 선택을 마지막 턴($lastTurn)까지 모두 둘 수 있었던 시드: ${through.size}. 그중 실제 승자($realWinner)가 이긴 시드 " +
                "${through.count { it.winner == realWinner }}, 상대가 이긴 시드 ${through.count { it.winner != null && it.winner != realWinner }}, " +
                "승부가 나지 않은 시드 ${through.count { it.winner == null }}")
            appendLine()
            appendLine("## 턴별 재현율")
            appendLine()
            appendLine("그 턴이 끝날 때까지 로그(피해량 제외)가 실제와 같은 시드의 비율이다.")
            appendLine()
            appendLine("| 턴 | 재현 시드 | 처음 갈라진 곳(가장 흔한 것) |")
            appendLine("|---|---|---|")
            val turnEnds = real.indices.filter { real[it].startsWith("|turn|") || real[it].startsWith("|win|") }
            var previous = 0
            for (end in turnEnds) {
                val label = if (real[end].startsWith("|win|")) "끝" else (real[end].split("|")[2].toInt() - 1).toString()
                val matched = reach.count { it > end }
                val parted = runs.indices.filter { reach[it] in previous..end }
                val common = parted.groupingBy { i -> "실제 `${real[reach[i]]}` / 엔진 `${runs[i].lines.getOrNull(reach[i]) ?: "(끝)"}`" }
                    .eachCount().maxByOrNull { it.value }
                if (label != "0") appendLine("| $label | $matched (${"%.1f".format(matched * 100.0 / seeds)}%) | ${common?.let { "${it.key} ×${it.value}" } ?: ""} |")
                previous = end + 1
            }
            appendLine()
            appendLine("## HP 비교")
            appendLine()
            appendLine("그 줄까지 실제와 같은 흐름을 탄 시드에서 엔진이 낸 HP(%) 범위다. 실제 값이 범위 밖이면 표시한다.")
            appendLine()
            appendLine("| 줄 | 실제 | 엔진 최소–중앙–최대 | 시드 |")
            appendLine("|---|---|---|---|")
            for (row in stats.rows) {
                val values = row.values
                if (values.isEmpty()) continue
                appendLine("| `${row.line.replace("|", "\\|")}` | ${row.real} | ${values.first()}–${values[values.size / 2]}–${values.last()} | ${values.size} |" +
                    if (row.outside > 0) " **범위 밖**" else "")
            }
            val stops = runs.mapNotNull { it.stoppedAt }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(5)
            if (stops.isNotEmpty()) {
                appendLine()
                appendLine("## 실제 선택을 더 이어 갈 수 없었던 이유")
                appendLine()
                stops.forEach { appendLine("- ${it.key} ×${it.value}") }
            }
        }
    }

    private fun report(id: String, text: String) {
        val dir = Path.of(System.getProperty("aiengine.coverage") ?: "build/reports/x").parent
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("ai-engine-replay-$id.md"), text)
    }

    @Test
    fun `real battles replay on the engine`() {
        for ((id, format) in replays) {
            val replay = parse(id, format)
            val text = mock(replay, MOCK_SEEDS)
            report(id, text)
            // The real log and the first seed's log side by side, for reading where they part.
            val dir = Path.of(System.getProperty("aiengine.coverage") ?: "build/reports/x").parent
            Files.write(dir.resolve("ai-engine-replay-$id-real.log"), replay.lines)
            Files.write(dir.resolve("ai-engine-replay-$id-seed.log"), play(replay, intArrayOf(1, 2, 3, 4)).lines)
            println(text.lineSequence().take(8).joinToString("\n"))
            // Some seed must play the real battle out: same order, knockouts, switches and winner.
            assertTrue("급소와 피해량 무시): 0 /" !in text) { "No seed reproduces $id:\n$text" }
        }
    }

    /**
     * The first seed (in the mock's seed order) whose battle follows the whole real log, or the one that
     * follows it furthest. Stops at the first faithful seed instead of playing them all.
     */
    private fun faithfulRun(replay: Replay, limit: Int = 2000): Run {
        val random = Random(20251215)
        val real = replay.lines.map(::shape)
        var best: Pair<Run, Int>? = null
        repeat(limit) {
            val run = play(replay, IntArray(4) { random.nextInt(65536) })
            val reach = real.indices.firstOrNull { run.lines.getOrNull(it)?.let(::shape) != real[it] } ?: real.size
            if (reach == real.size) return run
            if (best == null || reach > best!!.second) best = run to reach
        }
        return best!!.first
    }

    /**
     * Opt-in (-Psweeps): picks, one Pokemon at a time, the usage spread that best explains the real HP lines,
     * and writes the result to build/reports for copying next to the replay as `<id>.spreads.json`.
     */
    @Test
    fun `fit spreads to real battles`() {
        Assumptions.assumeTrue(System.getProperty("betterai.sweeps") == "true", "opt-in with -Psweeps")
        for ((id, format) in replays) {
            var replay = parse(id, format)
            var best = misfit(stats(replay, FIT_SEEDS))
            repeat(2) {
                for ((key, options) in replay.candidates) {
                    for (option in options) {
                        if (option == replay.spreads[key]) continue
                        val trial = replay.copy(spreads = replay.spreads + (key to option))
                        val score = misfit(stats(trial, FIT_SEEDS))
                        if (score < best) { best = score; replay = trial }
                    }
                }
            }
            val json = GsonBuilder().setPrettyPrinting().create().toJson(replay.spreads.toSortedMap())
            val dir = Path.of(System.getProperty("aiengine.coverage") ?: "build/reports/x").parent
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("$id.spreads.json"), json)
            println("$id misfit=$best\n$json")
        }
    }

    /**
     * Asks our AI, at every request of the real battle, what it would do (see [EngineReplayAiReview]). The
     * battle follows the real choices on Showdown under the seed that reproduces the replay best, so each
     * request is the position the players really faced. Writes `ai-engine-replay-<id>-ai.md`.
     */
    @Test
    fun `our AI reviews every real decision`() {
        EngineReferee.assumeAvailable()
        val engine = Path.of(System.getProperty("aiengine.showdown"))
        for ((id, format) in replays) {
            val replay = parse(id, format)
            val run = faithfulRun(replay)
            val directory = Files.createTempDirectory("ai-engine-review")
            val result = try {
                EngineReplayAiReview.run(replay.teams, replay.gameType, run.seed, run.choices, engine, directory.resolve("battle"))
            } finally {
                runCatching { Files.walk(directory).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
            }
            // The bridge names Pokemon by the UUIDs it hands out: side * 100 + slot + 1.
            val species = replay.teams.flatMap { (side, team) ->
                team.mapIndexed { i, s -> "00000000-0000-0000-0000-" + ((side.drop(1).toInt()) * 100 + i + 1).toString().padStart(12, '0') to s.species }
            }.toMap()
            val bridgeLines = result.publicLog.dropWhile { it != "|start" }
                .mapNotNull { normalize(it, { ident -> species[ident.substringAfter(": ")] }, emptyMap()) }.map(::shape)
            // The bridge only passes the event kinds it knows (4x hits are "-extremelyeffective" in Cobblemon and
            // do not reach the AI), so both logs are compared on the kinds the bridge kept.
            val kept = bridgeLines.map { it.split("|").getOrNull(1) }.toSet() - setOf("-supereffective", "-resisted")
            val realShapes = replay.lines.map(::shape).filter { it.split("|").getOrNull(1) in kept }
            val bridgeShapes = bridgeLines.filter { it.split("|").getOrNull(1) in kept }
            Files.write(Path.of(System.getProperty("aiengine.coverage") ?: "build/reports/x").parent.resolve("ai-engine-replay-$id-bridge.log"), bridgeLines)
            val follows = realShapes.indices.firstOrNull { bridgeShapes.getOrNull(it) != realShapes[it] } ?: realShapes.size
            val text = buildString {
                appendLine("# ${replay.id}: 실제 선택과 우리 AI의 선택")
                appendLine()
                appendLine("- 시드 ${run.seed.joinToString(",")}로 실제 선택을 Showdown에서 재생했다. 로그(피해량 제외)는 실제와 $follows / ${realShapes.size}줄까지 같다.")
                appendLine("- AI 입력은 embedded team battle과 같다(공개 로그 + 요청, 공개 팀 시트의 상대 기술). 팀 프리뷰와 자기 팀 정보를 채우지 않아 네이티브 탐색이 아니라 로컬 탐색 경로로 판단한다.")
                result.stoppedAt?.let { appendLine("- 중단: $it") }
                appendLine()
                for (row in result.rows) {
                    appendLine("## ${row.turn}턴 ${row.side}${if (row.forced) " (교체 요청)" else ""}")
                    appendLine()
                    appendLine("- 상황: ${row.board}")
                    appendLine("- 실제: ${row.real}")
                    if (row.failure != null) {
                        appendLine("- **AI 예외**: ${row.failure}")
                    } else {
                        appendLine("- AI: ${row.ai}${if (row.ai == row.real) " (같음)" else ""}  `${row.aiRaw}` ${row.millis}ms")
                        appendLine("- 태그: ${row.tags.joinToString(", ")}")
                        row.openingAccepted?.let { appendLine("- 네이티브 오프닝 조건 충족: $it") }
                        appendLine("- 후보 순위: " + row.ranked.take(8).joinToString(" / "))
                    }
                    appendLine()
                }
            }
            report("$id-ai", text)
            report("$id-ai-full", result.rows.joinToString("\n\n") { row ->
                "## ${row.turn}턴 ${row.side}${if (row.forced) " (교체 요청)" else ""}\n" + row.ranked.joinToString("\n") { "- $it" } +
                    (if (row.matchups.isEmpty()) "" else "\n\n대면 점수\n\n" + row.matchups.joinToString("\n") { "- $it" })
            })
            println(text.lineSequence().take(6).joinToString("\n"))
        }
    }

    @Test
    fun `real battles replay the same on Showdown`() {
        EngineReferee.assumeAvailable()
        for ((id, format) in replays) {
            val replay = parse(id, format)
            val random = Random(7)
            val scenarios = (0 until 8).map { n ->
                val seed = IntArray(4) { random.nextInt(65536) }
                RefScenario("$id-$n", replay.teams["p1"].orEmpty(), replay.teams["p2"].orEmpty(), play(replay, seed).choices,
                    seed = seed, gameType = replay.gameType)
            }
            val differences = EngineReferee.compare(scenarios)
            assertTrue(differences.isEmpty()) { differences.take(2).joinToString("\n") }
        }
    }
}

package jbro.cobblemon.mcc.betterai.engine

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import jbro.cobblemon.mcc.betterai.engine.dex.EngineDex
import jbro.cobblemon.mcc.betterai.engine.sim.Battle
import jbro.cobblemon.mcc.betterai.engine.sim.BattleOptions
import jbro.cobblemon.mcc.betterai.engine.sim.PokemonSet
import jbro.cobblemon.mcc.betterai.engine.sim.Side
import org.junit.jupiter.api.Assumptions

/** One team member, written once and handed to both Showdown (as JSON) and the engine (as a [PokemonSet]). */
data class RefSet(
    val species: String,
    val moves: List<String>,
    val ability: String = "",
    val item: String = "",
    val level: Int = 50,
    val nature: String = "Serious",
    val evs: Map<String, Int> = emptyMap(),
    val ivs: Map<String, Int> = emptyMap(),
    val gender: String = "M",
    val teraType: String? = null,
    val name: String? = null,
    /** A real battle's UUID instead of the readable default, for code that requires one. */
    val fixedUuid: String? = null,
) {
    fun uuid(side: String, index: Int) = fixedUuid ?: "$side-$index-${species.lowercase().replace(Regex("[^a-z0-9]"), "")}"

    fun toJson(side: String, index: Int): JsonObject = JsonObject().apply {
        addProperty("species", species)
        addProperty("name", name ?: species)
        addProperty("uuid", uuid(side, index))
        addProperty("level", level)
        addProperty("gender", gender)
        addProperty("ability", ability)
        addProperty("item", item)
        addProperty("nature", nature)
        teraType?.let { addProperty("teraType", it) }
        add("moves", JsonArray().also { a -> moves.forEach { a.add(it) } })
        add("evs", stats(evs, 0))
        add("ivs", stats(ivs, 31))
    }

    fun toSet(side: String, index: Int): PokemonSet = PokemonSet(
        species = species, name = name ?: species, level = level, gender = gender, ability = ability, item = item,
        nature = nature, evs = STATS.associateWith { evs[it] ?: 0 }.toMutableMap(),
        ivs = STATS.associateWith { ivs[it] ?: 31 }.toMutableMap(), moves = moves, teraType = teraType, uuid = uuid(side, index),
    )

    private fun stats(values: Map<String, Int>, default: Int) = JsonObject().apply { STATS.forEach { addProperty(it, values[it] ?: default) } }

    companion object {
        val STATS = listOf("hp", "atk", "def", "spa", "spd", "spe")
    }
}

data class RefScenario(
    val id: String,
    val p1: List<RefSet>,
    val p2: List<RefSet>,
    val turns: List<Pair<String, String>>,
    val seed: IntArray = intArrayOf(1, 2, 3, 4),
    val gameType: String = "singles",
    /** Record every PRNG roll on both sides, to find where the random sequences part. */
    val traceRng: Boolean = false,
    /** Have Showdown serialize the battle after every step, for [EngineReferee.resume]. */
    val snapshots: Boolean = false,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("gameType", gameType)
        if (traceRng) addProperty("traceRng", true)
        if (snapshots) addProperty("snapshots", true)
        add("seed", JsonArray().also { a -> seed.forEach { a.add(it) } })
        add("p1", JsonArray().also { a -> p1.forEachIndexed { i, s -> a.add(s.toJson("p1", i)) } })
        add("p2", JsonArray().also { a -> p2.forEachIndexed { i, s -> a.add(s.toJson("p2", i)) } })
        add("turns", JsonArray().also { a -> turns.forEach { (x, y) -> a.add(JsonArray().also { t -> t.add(x); t.add(y) }) } })
    }

    fun withSeed(seed: IntArray, suffix: String = seed.joinToString("-")) = copy(id = "$id@$suffix", seed = seed)
}

class RefResult(val id: String, val log: List<String>, val error: String?, val ended: Boolean, val turn: Int,
                val missingHooks: Set<String> = emptySet(), val rejections: List<String> = emptyList(),
                val states: List<String> = emptyList(), val rngTrace: List<String> = emptyList(),
                val snapshots: List<String> = emptyList(), val logLengths: List<Int> = emptyList())

/** Runs scenarios on the dev server's Showdown and on the engine. */
object EngineReferee {
    private val showdown: Path? = System.getProperty("aiengine.showdown")?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }
    private val tools: Path = Path.of(System.getProperty("aiengine.tools") ?: "tools/ai-engine")
    val dex: EngineDex by lazy { EngineDex.bundled() }

    /** Test runs used to leave every referee input and log behind; a full sweep writes tens of megabytes. */
    private fun deleteTree(root: Path) {
        runCatching { Files.walk(root).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }

    fun assumeAvailable() {
        Assumptions.assumeTrue(showdown != null, "No dev server Showdown; pass -PshowdownRoot=<repo>/dev-server/showdown")
    }

    private val msdDir: Path? by lazy {
        val mods = showdown?.parent?.resolve("mods") ?: return@lazy null
        val jar = Files.list(mods).use { s -> s.filter { it.fileName.toString().startsWith("mega_showdown-") }.findFirst().orElse(null) }
            ?: return@lazy null
        val out = Files.createTempDirectory("ai-engine-msd")
        Runtime.getRuntime().addShutdownHook(Thread { deleteTree(out) })
        val prefix = "data/mega_showdown/mega_showdown/showdown/"
        ZipFile(jar.toFile()).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory || !entry.name.startsWith(prefix) || !entry.name.endsWith(".js")) continue
                val target = out.resolve(entry.name.removePrefix(prefix))
                Files.createDirectories(target.parent)
                zip.getInputStream(entry).use { Files.copy(it, target) }
            }
        }
        out
    }

    fun showdown(scenarios: List<RefScenario>): Map<String, RefResult> {
        assumeAvailable()
        val dir = Files.createTempDirectory("ai-engine-referee")
        val input = dir.resolve("scenarios.json")
        val output = dir.resolve("result.json")
        Files.writeString(input, JsonObject().apply { add("scenarios", JsonArray().also { a -> scenarios.forEach { a.add(it.toJson()) } }) }.toString())
        val command = mutableListOf("node", tools.resolve("referee.cjs").toString(), "--showdown", showdown.toString(),
            "--in", input.toString(), "--out", output.toString())
        msdDir?.let { command += listOf("--msd", it.toString()) }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val console = process.inputStream.bufferedReader().readText()
        check(process.waitFor(120, TimeUnit.SECONDS)) { "Referee timed out" }
        check(process.exitValue() == 0) { "Referee failed: $console" }
        val root = JsonParser.parseString(Files.readString(output)).asJsonObject
        deleteTree(dir)
        return root.getAsJsonArray("results").associate { element ->
            val r = element.asJsonObject
            r.get("id").asString to RefResult(
                r.get("id").asString, r.getAsJsonArray("log").map { it.asString },
                r.get("error")?.takeIf { !it.isJsonNull }?.asString, r.get("ended").asBoolean, r.get("turn").asInt,
                rejections = r.getAsJsonArray("rejections")?.map { it.asString } ?: emptyList(),
                states = r.getAsJsonArray("states")?.map { it.asString } ?: emptyList(),
                rngTrace = r.getAsJsonArray("rngTrace")?.map { it.asString } ?: emptyList(),
                snapshots = r.getAsJsonArray("snapshots")?.map { it.asString } ?: emptyList(),
                logLengths = r.getAsJsonArray("logLengths")?.map { it.asInt } ?: emptyList(),
            )
        }
    }

    fun engine(scenario: RefScenario): RefResult {
        val battle = Battle(dex, BattleOptions(gameType = scenario.gameType, seed = scenario.seed))
        var error: String? = null
        val states = ArrayList<String>()
        val rng = if (scenario.traceRng) ArrayList<String>().also { battle.prng.trace = it } else null
        try {
            battle.setPlayer("p1", "p1", scenario.p1.mapIndexed { i, s -> s.toSet("p1", i) })
            battle.setPlayer("p2", "p2", scenario.p2.mapIndexed { i, s -> s.toSet("p2", i) })
            for ((a, b) in scenario.turns) {
                if (battle.ended) break
                choose(battle.sides[0], a)
                choose(battle.sides[1], b)
                battle.commitDecisions()
                states += stateLine(battle)
            }
        } catch (e: Throwable) {
            error = e.stackTraceToString().lines().take(12).joinToString("\n")
        }
        return RefResult(scenario.id, battle.log.filter { !it.startsWith("|t:|") }, error, battle.ended, battle.turn,
            battle.missingHooks.toSet(), states = states, rngTrace = rng ?: emptyList())
    }

    /**
     * Showdown's battle as it stood after step [step] of [scenario], read by the engine and played on from there.
     * The result holds only what came after: the log lines past [RefResult.logLengths] and the later states.
     */
    fun resume(scenario: RefScenario, showdown: RefResult, step: Int): Pair<RefResult, RefResult> {
        val tail = RefResult(showdown.id, showdown.log.drop(showdown.logLengths[step]), showdown.error, showdown.ended,
            showdown.turn, rejections = showdown.rejections, states = showdown.states.drop(step + 1))
        var error: String? = null
        val states = ArrayList<String>()
        var battle: Battle? = null
        var unknown = emptySet<String>()
        try {
            val read = jbro.cobblemon.mcc.betterai.engine.sim.ShowdownStateReader.read(dex, showdown.snapshots[step], keepLog = true)
            unknown = read.unknownKeys
            battle = read.battle
            for ((a, b) in scenario.turns.drop(step + 1)) {
                if (battle.ended) break
                choose(battle.sides[0], a)
                choose(battle.sides[1], b)
                battle.commitDecisions()
                states += stateLine(battle)
            }
        } catch (e: Throwable) {
            error = e.stackTraceToString().lines().take(12).joinToString("\n")
        }
        val log = battle?.log?.filter { !it.startsWith("|t:|") }?.drop(showdown.logLengths[step]) ?: emptyList()
        return tail to RefResult(scenario.id, log, error, battle?.ended ?: false, battle?.turn ?: 0,
            battle?.missingHooks?.toSet() ?: emptySet(), states = states, rejections = unknown.toList())
    }

    /**
     * The engine's own battle after step [step], written as Showdown writes one and read back: both play the rest of
     * [scenario], and the round trip must not change a line.
     */
    fun roundTrip(scenario: RefScenario, step: Int): Pair<RefResult, RefResult> {
        val battle = Battle(dex, BattleOptions(gameType = scenario.gameType, seed = scenario.seed))
        battle.setPlayer("p1", "p1", scenario.p1.mapIndexed { i, s -> s.toSet("p1", i) })
        battle.setPlayer("p2", "p2", scenario.p2.mapIndexed { i, s -> s.toSet("p2", i) })
        for ((a, b) in scenario.turns.take(step + 1)) {
            if (battle.ended) break
            choose(battle.sides[0], a)
            choose(battle.sides[1], b)
            battle.commitDecisions()
        }
        val written = jbro.cobblemon.mcc.betterai.engine.sim.ShowdownStateWriter.write(battle, includeLog = true).toString()
        val prefix = battle.log.size
        fun playOn(b: Battle, id: String): RefResult {
            var error: String? = null
            val states = ArrayList<String>()
            try {
                for ((a, c) in scenario.turns.drop(step + 1)) {
                    if (b.ended) break
                    choose(b.sides[0], a)
                    choose(b.sides[1], c)
                    b.commitDecisions()
                    states += stateLine(b)
                }
            } catch (e: Throwable) {
                error = e.stackTraceToString().lines().take(12).joinToString("\n")
            }
            return RefResult(id, b.log.drop(prefix).filter { !it.startsWith("|t:|") }, error, b.ended, b.turn, states = states)
        }
        val read = try {
            jbro.cobblemon.mcc.betterai.engine.sim.ShowdownStateReader.read(dex, written, keepLog = true).battle
        } catch (e: Throwable) {
            return RefResult(scenario.id, emptyList(), null, false, 0) to
                RefResult(scenario.id, emptyList(), e.stackTraceToString().lines().take(12).joinToString("\n"), false, 0)
        }
        return playOn(battle, scenario.id) to playOn(read, scenario.id)
    }

    /** Mirrors the referee script: skip a waiting side, auto-replace a fainted one, fall back when rejected. */
    private fun choose(side: Side, input: String) {
        val state = side.requestState
        if (state.isEmpty()) return
        var choice = input.ifEmpty { "default" }
        if (state == "switch" && (choice == "default" || "move" in choice)) choice = firstSwitch(side)
        try {
            if (side.choose(choice)) return
        } catch (_: IllegalArgumentException) {
            // fall back below
        }
        side.choose(if (state == "switch") firstSwitch(side) else "default")
    }

    /** Mirrors the referee's stateLine: team order, HP, and each request's moves with targets. */
    private fun stateLine(battle: Battle): String = battle.sides.joinToString(" ") { side ->
        val team = side.pokemon.joinToString(",") { "${it.uuid}:${it.hp}:${if (it.fainted) "F" else ""}" }
        val request = side.activeRequest
        val moves = when {
            request?.active != null -> request.active.joinToString("|") { a ->
                a?.moves?.joinToString("+") { m -> "${m.id}/${m.target}/${if (Js.truthy(m.disabled)) 1 else 0}" } ?: "-"
            }
            request?.forceSwitch != null -> "switch:" + request.forceSwitch.joinToString("") { if (it) "1" else "0" }
            request?.wait == true -> "wait"
            else -> ""
        }
        "${side.id}[$team]{$moves}"
    } + " rng=" + battle.prng.seed.joinToString(".")

    /** One choice per active slot: slots that must be replaced take the next healthy bench Pokemon. */
    private fun firstSwitch(side: Side): String {
        val used = HashSet<Int>()
        return side.active.joinToString(", ") { pokemon ->
            if (pokemon == null || !Js.truthy(pokemon.switchFlag)) return@joinToString "pass"
            val bench = (side.active.size until side.pokemon.size).firstOrNull { !side.pokemon[it].fainted && it !in used }
            if (bench == null) "pass" else "switch ${bench + 1}".also { used.add(bench) }
        }
    }

    /** The first line where the logs part ways, with context, or null when they match. */
    fun difference(showdown: RefResult, engine: RefResult): String? {
        val a = showdown.log
        val b = engine.log
        val first = (0 until maxOf(a.size, b.size)).firstOrNull { a.getOrNull(it) != b.getOrNull(it) }
        val firstState = (0 until maxOf(showdown.states.size, engine.states.size))
            .firstOrNull { showdown.states.getOrNull(it) != engine.states.getOrNull(it) }
        if (first == null && firstState == null && showdown.error == null && engine.error == null) return null
        val from = maxOf(0, (first ?: a.size) - 6)
        val to = (first ?: a.size) + 4
        return buildString {
            appendLine("scenario ${showdown.id}: logs differ at line ${first ?: "-"}")
            showdown.error?.let { appendLine("showdown error: $it") }
            showdown.rejections.forEach { appendLine("showdown $it") }
            if (showdown.rngTrace.isNotEmpty() || engine.rngTrace.isNotEmpty()) {
                val a = showdown.rngTrace
                val b = engine.rngTrace
                val at = (0 until maxOf(a.size, b.size)).firstOrNull { a.getOrNull(it)?.substringBefore(" @") != b.getOrNull(it)?.substringBefore(" @") }
                if (at != null) {
                    appendLine("rolls part at call $at:")
                    for (i in maxOf(0, at - 4)..minOf(at + 2, maxOf(a.size, b.size) - 1)) {
                        appendLine("  $i showdown: ${a.getOrNull(i)}")
                        appendLine("  $i engine:   ${b.getOrNull(i)}")
                    }
                }
            }
            if (firstState != null) {
                appendLine("hidden state differs after step $firstState:")
                appendLine("  showdown: ${showdown.states.getOrNull(firstState)}")
                appendLine("  engine:   ${engine.states.getOrNull(firstState)}")
            }
            engine.error?.let { appendLine("engine error: $it") }
            if (engine.missingHooks.isNotEmpty()) appendLine("unported handlers reached: ${engine.missingHooks}")
            for (i in from until minOf(to, maxOf(a.size, b.size))) {
                val same = a.getOrNull(i) == b.getOrNull(i)
                appendLine("${if (same) " " else "!"} $i showdown: ${a.getOrNull(i)}")
                if (!same) appendLine("${" ".repeat(i.toString().length + 2)}  engine:   ${b.getOrNull(i)}")
            }
        }
    }

    /** Plays every scenario on both sides and returns the differences (empty when all match). */
    fun compare(scenarios: List<RefScenario>): List<String> {
        val reference = showdown(scenarios)
        return scenarios.mapNotNull { s -> difference(reference.getValue(s.id), engine(s)) }
    }
}

/**
 * The coverage file the workbook generator reads: `{entries:[{category,id,effect,status,test}]}`. An entry
 * is written only after its referee test passed, which is what paints its row yellow.
 */
object EngineCoverage {
    private val file: Path = Path.of(System.getProperty("aiengine.coverage") ?: "build/reports/ai-engine-coverage.json")
    private val gson = GsonBuilder().setPrettyPrinting().create()

    /** Drops what an earlier run of [test] recorded, so entries that stopped passing lose their mark. */
    @Synchronized
    fun clear(test: String) {
        if (!Files.exists(file)) return
        val root = JsonParser.parseString(Files.readString(file)).asJsonObject
        val entries = root.getAsJsonArray("entries") ?: return
        val kept = JsonArray()
        entries.filter { it.asJsonObject.get("test")?.asString != test }.forEach { kept.add(it) }
        root.add("entries", kept)
        Files.writeString(file, gson.toJson(root))
    }

    @Synchronized
    fun record(category: String, id: String, effect: String, test: String) {
        Files.createDirectories(file.parent)
        val root = if (Files.exists(file)) JsonParser.parseString(Files.readString(file)).asJsonObject else JsonObject()
        val entries = root.getAsJsonArray("entries") ?: JsonArray().also { root.add("entries", it) }
        val existing = entries.firstOrNull { e ->
            val o = e.asJsonObject
            o.get("category").asString == category && o.get("id").asString == id && o.get("effect").asString == effect
        }
        if (existing != null) entries.remove(existing)
        entries.add(JsonObject().apply {
            addProperty("category", category)
            addProperty("id", id)
            addProperty("effect", effect)
            addProperty("status", "적용 완료")
            addProperty("test", test)
        })
        Files.writeString(file, gson.toJson(root))
    }
}

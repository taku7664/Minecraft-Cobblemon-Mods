package jbro.cobblemon.mcc.betterai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleBrainContentIds
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

/**
 * Play against the boss from a phone (`works/tools/betterai-arena/play-server.bat`): one battle at a time, p1 played by
 * hand through a small web page, p2 the brain at the boss tier as in the game (searchless, the team preview and the
 * boss's exact spreads handed over). Teams come from the preset sets, drawn by a seed.
 *
 * Every route sits under `/<TOKEN>/`, so only someone given the link reaches it. Each battle keeps its folder
 * (decisions.jsonl with the boss's rows, result.json) under OUT/<time>.
 *
 * `java -cp <mod JAR>;<arena runner JAR + lib> jbro.cobblemon.mcc.betterai.BetterAiPlayServerKt PORT TOKEN OUT SHOWDOWN PAGE`
 */
fun main(args: Array<String>) {
    val port = args[0].toInt()
    val token = args[1]
    require(token.length >= 16 && token.all(Char::isLetterOrDigit)) { "the token must be 16+ letters or digits" }
    val out = Path.of(args[2])
    val showdown = Path.of(args[3])
    val page = Path.of(args[4])
    MegaShowdownTestSpecies.install()
    val worker = jbro.cobblemon.mcc.betterai.simulation.EngineBranchWorker()
    jbro.cobblemon.mcc.betterai.search.LocalEngineStopProbe.lease = object : jbro.cobblemon.mcc.betterai.search.LocalEngineStopProbe.Lease {
        override fun <T> withWorker(deadlineNanos: Long, action: (jbro.cobblemon.mcc.betterai.simulation.NativeBranchWorker) -> T): T? =
            synchronized(worker) { action(worker) }
    }
    val play = PlayServer(out, showdown)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 16)
    server.executor = Executors.newFixedThreadPool(4)
    server.createContext("/") { exchange ->
        runCatching { route(exchange, token, page, play) }.onFailure {
            // The page reads every answer as JSON, failures too.
            runCatching { reply(exchange, 500, "application/json; charset=utf-8",
                JsonObject().apply { addProperty("error", it.message ?: it.toString()) }.toString().toByteArray(Charsets.UTF_8)) }
        }
        exchange.close()
    }
    server.start()
    println("PLAY {\"event\":\"listening\",\"port\":$port}")
}

private fun route(exchange: HttpExchange, token: String, page: Path, play: PlayServer) {
    val path = exchange.requestURI.path
    val prefix = "/$token"
    val given = path.removePrefix("/").substringBefore('/')
    if (!MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) return reply(exchange, 404, "text/plain", "not found".toByteArray())
    val rest = path.removePrefix(prefix)
    when {
        rest.isEmpty() -> {
            exchange.responseHeaders.add("Location", "$prefix/")
            reply(exchange, 302, "text/plain", ByteArray(0))
        }
        rest == "/" -> reply(exchange, 200, "text/html; charset=utf-8", Files.readAllBytes(page))
        rest == "/api/names" -> json(exchange, play.names)
        rest == "/api/state" -> json(exchange, play.state())
        rest == "/api/start" && exchange.requestMethod == "POST" -> json(exchange, play.start(body(exchange)))
        rest == "/api/choose" && exchange.requestMethod == "POST" -> json(exchange, play.choose(body(exchange)))
        rest == "/api/records" -> json(exchange, play.records())
        rest == "/api/record" -> json(exchange, play.record(query(exchange)["id"].orEmpty()))
        rest == "/api/record-file" -> {
            val q = query(exchange)
            val file = play.recordFile(q["id"].orEmpty(), q["name"].orEmpty())
                ?: return reply(exchange, 404, "text/plain", "not found".toByteArray())
            exchange.responseHeaders.add("Content-Disposition", "attachment; filename=\"${q["id"]}-${q["name"]}\"")
            reply(exchange, 200, if (q["name"]!!.endsWith(".json")) "application/json" else "application/x-ndjson", Files.readAllBytes(file))
        }
        else -> reply(exchange, 404, "text/plain", "not found".toByteArray())
    }
}

/** The request's JSON (two six-member entries run to ~10 KB; 256 KB is the cap). Unreadable JSON is an error, not "no entry". */
private fun body(exchange: HttpExchange): JsonObject {
    val text = exchange.requestBody.readNBytes(256 * 1024).toString(Charsets.UTF_8)
    if (text.isBlank()) return JsonObject()
    return runCatching { JsonParser.parseString(text).asJsonObject }
        .getOrElse { throw IllegalArgumentException("요청을 읽지 못했습니다: ${it.message}") }
}

private fun query(exchange: HttpExchange): Map<String, String> =
    exchange.requestURI.rawQuery.orEmpty().split('&').filter { '=' in it }.associate {
        java.net.URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to java.net.URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
    }

private fun json(exchange: HttpExchange, value: JsonObject) =
    reply(exchange, 200, "application/json; charset=utf-8", value.toString().toByteArray(Charsets.UTF_8))

private fun reply(exchange: HttpExchange, status: Int, type: String, bytes: ByteArray) {
    exchange.responseHeaders.add("Content-Type", type)
    exchange.responseHeaders.add("Cache-Control", "no-store")
    exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
    if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
}

private class PlayServer(private val out: Path, private val showdown: Path) {
    /**
     * Korean names from every `assets/cobblemon/lang/ko_kr.json` on the classpath, merged (ids lower-case, letters and
     * digits only). The mod JAR carries a small one of its own that would hide Cobblemon's; the first file wins a key.
     */
    val names: JsonObject = JsonObject().apply {
        val lang = JsonObject()
        javaClass.classLoader.getResources("assets/cobblemon/lang/ko_kr.json").asSequence().forEach { url ->
            url.openStream().use { JsonParser.parseReader(it.reader(Charsets.UTF_8)).asJsonObject }.entrySet()
                .forEach { (key, value) -> if (!lang.has(key)) lang.add(key, value) }
        }
        val groups = mapOf("species" to JsonObject(), "moves" to JsonObject(), "abilities" to JsonObject(), "items" to JsonObject())
        fun id(text: String) = text.lowercase().filter(Char::isLetterOrDigit)
        for ((key, value) in lang.entrySet()) {
            val parts = key.split('.')
            when {
                parts.size == 4 && parts[1] == "species" && parts[3] == "name" -> groups.getValue("species").addProperty(id(parts[2]), value.asString)
                parts.size == 3 && parts[1] == "move" -> groups.getValue("moves").addProperty(id(parts[2]), value.asString)
                parts.size == 3 && parts[1] == "ability" -> groups.getValue("abilities").addProperty(id(parts[2]), value.asString)
                parts.size == 3 && parts[0] == "item" && parts[1] == "cobblemon" -> groups.getValue("items").addProperty(id(parts[2]), value.asString)
            }
        }
        groups.forEach { (name, group) -> add(name, group) }
    }

    private var game: Game? = null

    @Synchronized
    fun state(): JsonObject = JsonObject().apply { game?.let { add("game", it.view()) } }

    private fun playing(folder: Path) = game?.folder == folder && game?.status !in setOf("done", "error", "stopped")

    /** Every battle folder under OUT (yyyyMMdd-HHmmss), newest first: its teams' species, how it ended. */
    @Synchronized
    fun records(): JsonObject = JsonObject().apply {
        add("records", JsonArray().apply {
            recordFolders().forEach { folder ->
                val battle = folder.resolve("battle")
                val referee = readJson(battle.resolve("referee-input.json"))
                val result = readJson(battle.resolve("result.json"))
                add(JsonObject().apply {
                    addProperty("id", folder.fileName.toString())
                    addProperty("status", when { playing(folder) -> "live"; result != null -> "done"; else -> "stopped" })
                    result?.let { addProperty("winner", it["winner"]?.takeUnless { w -> w.isJsonNull }?.asString); addProperty("turn", it["turn"]?.asInt) }
                    for (side in listOf("p1", "p2")) add(side, JsonArray().apply {
                        referee?.getAsJsonObject(side)?.getAsJsonArray("sets")?.forEach { set -> add(set.asJsonObject["species"]) }
                    })
                })
            }
        })
    }

    /**
     * One finished or abandoned battle: both teams' sets, the public log (the result's, or the last boss decision's for a
     * battle left before its end) and every boss decision condensed. A battle being played is not opened, so the boss's
     * sets stay hidden meanwhile.
     */
    @Synchronized
    fun record(id: String): JsonObject {
        val folder = recordFolders().firstOrNull { it.fileName.toString() == id }
            ?: return JsonObject().apply { addProperty("error", "그런 기록이 없습니다: $id") }
        if (playing(folder)) return JsonObject().apply { addProperty("error", "지금 두는 판은 끝난 뒤에 볼 수 있습니다") }
        val battle = folder.resolve("battle")
        val rows = battle.resolve("decisions.jsonl").takeIf(Files::exists)?.let { file ->
            Files.readAllLines(file).filter(String::isNotBlank).map { JsonParser.parseString(it).asJsonObject }
        }.orEmpty()
        val result = readJson(battle.resolve("result.json"))
        val log = result?.getAsJsonArray("publicLog") ?: rows.lastOrNull()?.getAsJsonObject("input")?.getAsJsonArray("publicLog")
        return JsonObject().apply {
            addProperty("id", id)
            addProperty("status", if (result != null) "done" else "stopped")
            result?.let { addProperty("winner", it["winner"]?.takeUnless { w -> w.isJsonNull }?.asString); addProperty("turn", it["turn"]?.asInt) }
            readJson(battle.resolve("referee-input.json"))?.let { referee ->
                add("teams", JsonObject().apply { for (side in listOf("p1", "p2")) add(side, referee.getAsJsonObject(side)?.getAsJsonArray("sets") ?: JsonArray()) })
            }
            add("log", JsonArray().apply { cleanLog(log).forEach(::add) })
            add("decisions", JsonArray().apply { rows.forEach { row -> bossDecision(row)?.let(::add) } })
            add("files", JsonArray().apply { RECORD_FILES.filter { Files.exists(battle.resolve(it)) }.forEach(::add) })
        }
    }

    /** A record file to hand the phone: only a battle's own files, and not while it is being played. */
    @Synchronized
    fun recordFile(id: String, name: String): Path? {
        if (name !in RECORD_FILES) return null
        val folder = recordFolders().firstOrNull { it.fileName.toString() == id } ?: return null
        if (playing(folder)) return null
        return folder.resolve("battle").resolve(name).takeIf(Files::exists)
    }

    private fun recordFolders(): List<Path> = if (!Files.isDirectory(out)) emptyList() else Files.list(out).use { files ->
        files.filter { Files.isDirectory(it) && RECORD_ID.matches(it.fileName.toString()) && Files.isDirectory(it.resolve("battle")) }
            .toList().sortedByDescending { it.fileName.toString() }
    }

    private fun readJson(path: Path): JsonObject? =
        path.takeIf(Files::exists)?.let { runCatching { JsonParser.parseString(Files.readString(it)).asJsonObject }.getOrNull() }

    private companion object {
        val RECORD_ID = Regex("""\d{8}-\d{6}""")
        val RECORD_FILES = listOf("result.json", "decisions.jsonl", "referee-input.json")
    }

    @Synchronized
    fun start(body: JsonObject): JsonObject {
        game?.stop()
        val seed = body["seed"]?.takeIf { it.isJsonPrimitive }?.asInt ?: (1..2_000_000_000).random()
        val entry = runCatching { entry(body) }.getOrElse { problem ->
            return state().apply { addProperty("error", problem.message ?: problem.toString()) }
        }
        val folder = out.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")))
        game = Game(folder, seed, showdown, entry).also { it.begin() }
        return state()
    }

    /**
     * Both sides' sets when the page sent them (`p1.sets`, `p2.sets`, 1-6 each, as the bridge reads them), else null for
     * a random preset pair. Each set gets the battle id the bridge reads its preview slot from (`...0000000001NN`).
     */
    private fun entry(body: JsonObject): JsonObject? {
        if (!body.has("p1") && !body.has("p2")) return null
        return JsonObject().apply {
            for ((digit, side) in listOf(1 to "p1", 2 to "p2")) {
                val sets = body.getAsJsonObject(side)?.getAsJsonArray("sets") ?: error("${if (side == "p1") "내" else "상대"} 엔트리가 비었습니다")
                require(sets.size() in 1..6) { "엔트리는 1~6마리입니다 (${side}: ${sets.size()}마리)" }
                add(side, JsonObject().apply {
                    add("sets", JsonArray().apply {
                        sets.forEachIndexed { index, value ->
                            val set = value.asJsonObject.deepCopy()
                            val species = set["species"]?.asString?.takeIf(String::isNotBlank) ?: error("${index + 1}번째 포켓몬의 종족이 없습니다")
                            val moves = set.getAsJsonArray("moves")
                            require(moves != null && moves.size() in 1..4) { "$species: 기술은 1~4개입니다" }
                            if (!set.has("name")) set.addProperty("name", species)
                            set.addProperty("uuid", "00000000-0000-0000-0000-0000000${digit}%04d".format(index + 1))
                            add(set)
                        }
                    })
                })
            }
        }
    }

    @Synchronized
    fun choose(body: JsonObject): JsonObject {
        val current = game ?: return JsonObject().apply { addProperty("error", "진행 중인 대전이 없습니다") }
        val action = body["action"]?.asString ?: return JsonObject().apply { addProperty("error", "고른 행동이 없습니다") }
        current.choose(action, body["request"]?.asInt)?.let { problem -> return state().apply { addProperty("error", problem) } }
        return state()
    }
}

private class Game(val folder: Path, val seed: Int, private val showdown: Path, private val entry: JsonObject?) {
    @Volatile var status = "starting"
    @Volatile var pending: JsonObject? = null
    @Volatile var result: JsonObject? = null
    @Volatile var error: String? = null
    @Volatile var teams: JsonObject? = null
    @Volatile var requestNumber = 0
    private val choices = LinkedBlockingQueue<String>()
    private var thread: Thread? = null

    fun view(): JsonObject = JsonObject().apply {
        addProperty("id", folder.fileName.toString())
        addProperty("seed", seed)
        addProperty("status", status)
        // The boss's sets stay hidden until the end, as in the game: only its species show (the team preview).
        teams?.let { all ->
            add("teams", if (status == "done") all else JsonObject().apply {
                add("p1", all["p1"])
                add("p2", JsonArray().apply { all.getAsJsonArray("p2").forEach { add(JsonObject().apply { add("species", it.asJsonObject["species"]) }) } })
            })
        }
        pending?.let { add("pending", it) }
        result?.let { add("result", it) }
        error?.let { addProperty("error", it) }
    }

    fun stop() {
        thread?.interrupt()
        if (status !in setOf("done", "error")) status = "stopped"
    }

    /** Null when taken, else what was wrong. */
    fun choose(action: String, request: Int?): String? {
        val waiting = pending ?: return "지금은 고를 차례가 아닙니다"
        if (request != null && request != waiting["request"].asInt) return "이미 지난 차례입니다"
        if (waiting.getAsJsonArray("options").none { it.asJsonObject["id"].asString == action }) return "고를 수 없는 행동입니다: $action"
        pending = null
        status = "ai_thinking"
        choices.put(action)
        return null
    }

    fun begin() {
        thread = Thread({ play() }, "play-${folder.fileName}").apply { isDaemon = true; start() }
    }

    private fun play() {
        val scratch = folder.resolve("engine")
        try {
            Files.createDirectories(folder)
            // The engine copy comes with the preset audit; a sent entry replaces the drawn pair.
            val audit = EmbeddedPresetAudit.run(scratch.resolve("audit"), teamPairs = if (entry == null) 1 else 0, teamSeed = seed)
            val engine = scratch.resolve("audit/engine")
            for (file in listOf("side.js", "pokemon.js", "battle.js", "battle-actions.js")) {
                Files.copy(showdown.resolve("sim/$file"), engine.resolve("sim/$file"), REPLACE_EXISTING)
            }
            val pair = if (entry == null) {
                audit.getAsJsonObject("teamSampling").getAsJsonArray("pairs").first().asJsonObject.deepCopy()
            } else {
                JsonObject().apply {
                    addProperty("battleFormat", "SINGLE")
                    add("battleSeed", JsonArray().apply { listOf(seed, seed * 7 + 1, seed * 13 + 2, seed * 31 + 3).forEach { add(it and 0xffff) } })
                    add("p1", entry["p1"].deepCopy())
                    add("p2", entry["p2"].deepCopy())
                }
            }.apply { addProperty("teamPreview", true) }
            teams = JsonObject().apply {
                for (side in listOf("p1", "p2")) add(side, pair.getAsJsonObject(side).getAsJsonArray("sets").deepCopy())
            }
            if (Thread.interrupted()) throw InterruptedException()
            val battle = folder.resolve("battle")
            val persona = BattleBrainContentIds.AI_TEST_PERSONA_PREFIX + "play"
            val outcome = EmbeddedTeamBattle.run(engine, pair, battle, 200, trainerProfile = BattleTrainerProfile.boss(),
                searchlessSides = mapOf("p1" to true, "p2" to true),
                trainerPersonaIds = mapOf("p2" to persona),
                manual = { side, context, input -> if (side != "p1") null else ask(context, input, battle) })
            result = JsonObject().apply {
                addProperty("winner", outcome["winner"]?.takeUnless { it.isJsonNull }?.asString)
                addProperty("turn", outcome["turn"]?.asInt)
                add("log", JsonArray().apply { cleanLog(outcome.getAsJsonArray("publicLog"), lastIds).forEach(::add) })
                lastAi(battle)?.let { add("ai", it) }
            }
            pending = null
            status = "done"
        } catch (stopped: InterruptedException) {
            status = "stopped"
        } catch (failure: Throwable) {
            if (status != "stopped") {
                error = failure.toString().take(2000)
                status = "error"
            }
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }

    private var lastIds: Map<String, String> = emptyMap()

    private fun ask(context: BattleDecisionContext, input: JsonObject, battle: Path): String {
        val ids = context.state.pokemon.associate { it.battlePokemonId.toString() to it.speciesId.substringAfter(':') }
        lastIds = lastIds + ids
        val request = input.getAsJsonObject("request")
        requestNumber++
        pending = JsonObject().apply {
            addProperty("request", requestNumber)
            addProperty("turn", context.state.turn)
            addProperty("forced", request.has("forceSwitch"))
            add("log", JsonArray().apply { cleanLog(input.getAsJsonArray("publicLog"), lastIds).forEach(::add) })
            add("board", JsonArray().apply {
                context.state.pokemon.sortedBy { it.side.ordinal }.forEach { p ->
                    add(JsonObject().apply {
                        addProperty("mine", p.side == BattleSide.ALLY)
                        addProperty("species", p.speciesId.substringAfter(':'))
                        addProperty("active", p.activeSlot != null)
                        addProperty("fainted", p.fainted)
                        addProperty("hp", p.hpFraction)
                        addProperty("status", p.statusId)
                        add("stages", JsonObject().apply { p.statStages.filterValues { it != 0 }.forEach { (k, v) -> addProperty(k.toString().lowercase(), v) } })
                        addProperty("ability", p.knownAbilityId)
                        addProperty("item", p.knownHeldItemId)
                        add("moves", JsonArray().apply { p.knownMoveIds.forEach { add(it.toString()) } })
                    })
                }
            })
            addProperty("field", context.state.field.toString())
            // Showdown's own view of my side: exact HP, PP and the moves of the active Pokemon.
            add("side", request.getAsJsonObject("side")?.deepCopy() ?: JsonObject())
            request.getAsJsonArray("active")?.let { add("active", it.deepCopy()) }
            add("options", JsonArray().apply {
                context.candidates.forEach { c ->
                    val parts = if (c.kind == BattleActionKind.COMPOSITE) c.componentActions else listOf(c)
                    add(JsonObject().apply {
                        addProperty("id", c.actionId)
                        add("parts", JsonArray().apply {
                            parts.forEach { part ->
                                add(JsonObject().apply {
                                    addProperty("kind", part.kind.name)
                                    part.moveId?.let { addProperty("move", it.toString()) }
                                    part.switchPokemonId?.let { id -> addProperty("switchTo", ids[id.toString()] ?: "?") }
                                    part.mechanic?.mechanicId?.let { addProperty("mechanic", it.toString()) }
                                    part.targets.singleOrNull()?.let { addProperty("target", "${it.side.name.lowercase()}${it.slot}") }
                                })
                            }
                        })
                    })
                }
            })
            lastAi(battle)?.let { add("ai", it) }
        }
        status = "your_turn"
        return choices.take()
    }

    /** The boss's latest decision, as [bossDecision] condenses it. */
    private fun lastAi(battle: Path): JsonObject? {
        val file = battle.resolve("decisions.jsonl")
        if (!Files.exists(file)) return null
        val line = Files.readAllLines(file).lastOrNull { it.isNotBlank() } ?: return null
        return bossDecision(JsonParser.parseString(line).asJsonObject)
    }

}

/** One boss decision row as the arena viewer shows it: top candidates, rule adjustments, draw chances. */
private fun bossDecision(row: JsonObject): JsonObject? {
    val stages = row.getAsJsonObject("stages") ?: return null
    val refined = stages.getAsJsonObject("refined") ?: return null
    val base = stages.getAsJsonObject("base") ?: JsonObject()
    val rules = stages.getAsJsonObject("rules") ?: JsonObject()
    val draws = stages.getAsJsonObject("drawProbabilities") ?: JsonObject()
    val moves = row.getAsJsonObject("candidateMoves") ?: JsonObject()
    val exclusions = JsonObject().apply {
        listOf("drawExclusions", "ruleExclusions").forEach { key -> stages.getAsJsonObject(key)?.entrySet()?.forEach { (k, v) -> add(k, v) } }
    }
    val ranked = refined.entrySet().sortedByDescending { it.value.asDouble }.map { it.key }
    val shown = (ranked.take(6) + listOfNotNull(row["actionId"]?.asString) + draws.keySet()).distinct()
    return JsonObject().apply {
        addProperty("turn", row["turn"].asInt)
        addProperty("action", row["actionId"].asString)
        add("candidates", JsonArray().apply {
            shown.filter { refined.has(it) }.forEach { key ->
                add(JsonObject().apply {
                    addProperty("id", key)
                    moves[key]?.let { addProperty("target", it.asString) }
                    addProperty("base", base[key]?.asDouble)
                    addProperty("refined", refined[key].asDouble)
                    draws[key]?.let { addProperty("p", it.asDouble) }
                    exclusions[key]?.let { addProperty("ex", it.asString) }
                    add("rules", JsonObject().apply {
                        rules.entrySet().forEach { (name, values) ->
                            values.asJsonObject[key]?.asDouble?.takeIf { kotlin.math.abs(it) >= 0.05 }?.let { addProperty(name, it) }
                        }
                    })
                })
            }
        })
        addProperty("hidden", (refined.size() - shown.count { refined.has(it) }).coerceAtLeast(0))
    }
}

/** The public log with each Pokemon's battle id replaced by its species, learned from the log's own switch lines. */
private fun cleanLog(log: JsonArray?, ids: Map<String, String> = emptyMap()): List<String> {
    val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    val lines = log?.map { it.asString }.orEmpty().filterNot { it.isBlank() || it.startsWith("|t:|") }
    val seen = ids.toMutableMap()
    val entry = Regex("""^\|(?:switch|drag|replace|detailschange)\|p\d[a-z]?: (${uuid.pattern})\|([^,|]+)""")
    lines.forEach { line -> entry.find(line)?.let { seen[it.groupValues[1]] = it.groupValues[2] } }
    return lines.map { line -> uuid.replace(line) { seen[it.value] ?: it.value.takeLast(4) } }
}

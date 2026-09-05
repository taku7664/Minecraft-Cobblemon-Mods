package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.evaluation.LocalDecisionTuning
import jbro.cobblemon.morebattlecontent.betterai.search.LocalLookaheadBudgetPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile

internal data class LocalScenarioDecisionTrace(
    val turn: Int,
    val side: String,
    val actionId: String,
    val elapsedNanos: Long,
    val source: String,
    val tags: List<String>,
)

internal data class LocalBaselineOptions(
    val battles: Int,
    val seed: Int,
    val maximumTurns: Int,
    val format: BattleFormat,
    val difficulty: BattleDifficultyProfile,
) {
    init {
        require(battles in 1..10_000)
        require(maximumTurns in 1..1_000)
        require(format == BattleFormat.SINGLE || format == BattleFormat.DOUBLE)
    }
}

internal data class LocalBaselineIdentity(
    val sourceRevision: String,
    val sourceSha256: String,
    val runtimeSha256: String,
)

/** Test-only recorder. It never opens server config, calls Router, or changes a running battle. */
internal object LocalBaselineCapture {
    private val json = GsonBuilder().setPrettyPrinting().serializeNulls().create()

    fun manifest(
        options: LocalBaselineOptions,
        identity: LocalBaselineIdentity,
        definitions: List<LocalTacticalScenarioDefinition> = LocalSelfPlayMeasurement.definitions(options.battles, options.seed, options.format),
    ): JsonObject {
        val ids = definitions.flatMap { it.cycleSetIds + it.offenseSetIds }.toSet()
        val roster = LocalTacticalSimulationRoster.loadAll().entries
        return JsonObject().apply {
            addProperty("schemaVersion", 1)
            addProperty("engine", "LOCAL_PROJECTOR_SELF_PLAY")
            addProperty("evidence", "HARNESS_BASELINE_NOT_INDEPENDENT_GAMEPLAY")
            addProperty("reproducibility", "SAME_INPUTS_ONLY_WALL_CLOCK_SEARCH_CAN_VARY")
            add("identity", json.toJsonTree(identity))
            add("options", json.toJsonTree(options))
            add("tuning", json.toJsonTree(LocalDecisionTuning.CURRENT))
            add("searchBudget", json.toJsonTree(LocalLookaheadBudgetPolicy.forTier(options.difficulty.tier)))
            add("personality", json.toJsonTree(BattleTrainerProfile.champion().personality))
            addProperty("strategy", "SCENARIO_CYCLE_VS_OFFENSE")
            addProperty("rosterSha256", digest(json.toJson(roster).toByteArray(Charsets.UTF_8)))
            add("definitions", json.toJsonTree(definitions))
            add("teams", json.toJsonTree(roster.filter { it.setId in ids }.sortedBy { it.setId }))
        }
    }

    /** Fail closed before executing a replay if code, runtime, inputs, or policy changed. */
    fun replayDefinitions(recorded: JsonObject, current: JsonObject): List<LocalTacticalScenarioDefinition> {
        for (field in listOf("schemaVersion", "engine", "evidence", "reproducibility", "options",
            "tuning", "searchBudget", "personality", "strategy", "rosterSha256", "definitions", "teams")) {
            require(recorded.has(field) && recorded[field] == current[field]) { "Baseline mismatch: $field" }
        }
        for (field in listOf("sourceSha256", "runtimeSha256")) {
            require(recorded.getAsJsonObject("identity")?.get(field) == current.getAsJsonObject("identity")[field]) {
                "Baseline mismatch: $field"
            }
        }
        // A commit after capture can have identical contents; revision is provenance, not compatibility.
        return recorded.getAsJsonArray("definitions").map {
            json.fromJson(it, LocalTacticalScenarioDefinition::class.java)
        }
    }

    fun createRun(directory: Path, manifest: JsonObject) {
        Files.createDirectories(directory.toAbsolutePath().parent)
        Files.createDirectory(directory)
        Files.writeString(directory.resolve("manifest.json"), json.toJson(manifest), CREATE_NEW)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 8) { "Expected root, output, battles, seed, maxTurns, format, tier, replay-or-empty" }
        val root = Path.of(args[0]).toAbsolutePath().normalize()
        val output = Path.of(args[1]).toAbsolutePath().normalize()
        val replay = args[7].takeIf { it.isNotBlank() }?.let { path ->
            Files.newBufferedReader(Path.of(path)).use { JsonParser.parseReader(it).asJsonObject }
        }
        val options = if (replay == null) {
            LocalBaselineOptions(args[2].toInt(), args[3].toInt(), args[4].toInt(),
                BattleFormat.valueOf(args[5]), difficulty(args[6]))
        } else {
            val saved = replay.getAsJsonObject("options")
            // Rebuild with constructors and current tier defaults rather than bypassing invariants via Gson.
            LocalBaselineOptions(saved["battles"].asInt, saved["seed"].asInt, saved["maximumTurns"].asInt,
                BattleFormat.valueOf(saved["format"].asString), difficulty(saved.getAsJsonObject("difficulty")["tier"].asString))
        }
        val identity = LocalBaselineProvenance.capture(root)
        val input = manifest(options, identity)
        val definitions = replayDefinitions(replay ?: input, input)
        createRun(output, input)
        val started = Instant.now()
        val results = output.resolve("battles.jsonl")
        var completed = 0
        // One line per completed battle survives interruption. No success summary is written on failure.
        Files.newBufferedWriter(results, CREATE_NEW).use { writer ->
            for (definition in definitions) {
                val trace = mutableListOf<LocalScenarioDecisionTrace>()
                val begin = System.nanoTime()
                val report = LocalTacticalScenarioBattle.run(definition, options.maximumTurns,
                    cycleDifficulty = options.difficulty, recordedDecisions = trace)
                val elapsed = System.nanoTime() - begin
                val record = JsonObject().apply {
                    add("report", json.toJsonTree(report))
                    add("decisions", json.toJsonTree(trace))
                    addProperty("elapsedNanos", elapsed)
                }
                writer.append(record.toString()).appendLine()
                writer.flush()
                completed++
                println("baseline battle=$completed/${definitions.size} decisions=${trace.size}")
            }
        }
        // Also detect edits made while the run was in progress, instead of labelling mixed inputs complete.
        check(LocalBaselineProvenance.capture(root) == identity) { "Inputs changed during baseline capture" }
        val summary = JsonObject().apply {
            addProperty("status", "COMPLETE")
            addProperty("completedBattles", completed)
            addProperty("startedAt", started.toString())
            addProperty("finishedAt", Instant.now().toString())
            addProperty("javaVersion", System.getProperty("java.version"))
            addProperty("os", System.getProperty("os.name"))
            addProperty("availableProcessors", Runtime.getRuntime().availableProcessors())
            addProperty("replayed", replay != null)
        }
        Files.writeString(output.resolve("summary.json"), json.toJson(summary), CREATE_NEW)
        println("baseline complete: $output")
    }

    internal fun difficulty(tier: String): BattleDifficultyProfile = when (tier) {
        "INTRODUCTORY" -> BattleDifficultyProfiles.INTRODUCTORY
        "STANDARD" -> BattleDifficultyProfiles.STANDARD
        "ADVANCED" -> BattleDifficultyProfiles.ADVANCED
        "BOSS" -> BattleDifficultyProfiles.BOSS
        else -> error("Unknown difficulty tier")
    }

    internal fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Hashes explicit inputs, never serializes arbitrary environment variables or config contents. */
internal object LocalBaselineProvenance {
    fun capture(root: Path): LocalBaselineIdentity {
        val process = ProcessBuilder("git", "-c", "safe.directory=$root", "rev-parse", "HEAD")
            .directory(root.toFile()).redirectErrorStream(true).start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Git revision lookup timed out")
        }
        val revision = process.inputStream.bufferedReader().use { it.readText().trim() }
        check(process.exitValue() == 0 && revision.matches(Regex("[0-9a-f]{40,64}"))) { "Cannot identify source revision" }
        val sourceFiles = mutableListOf<Path>()
        for (module in listOf("more-battle-content", "more-battle-content-better-ai")) {
            val source = root.resolve("$module/src")
            Files.walk(source).use { stream -> sourceFiles.addAll(stream.filter { Files.isRegularFile(it) }.toList()) }
            sourceFiles.add(root.resolve("$module/build.gradle.kts"))
        }
        for (file in listOf("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle/libs.versions.toml")) {
            root.resolve(file).takeIf { Files.isRegularFile(it) }?.let { sourceFiles.add(it) }
        }
        val sourceHash = hashEntries(sourceFiles.map { root.relativize(it).toString().replace('\\', '/') to it })
        val runtimeFiles = mutableListOf<Pair<String, Path>>()
        val absentRuntimeEntries = mutableListOf<Int>()
        effectiveClasspath(System.getProperty("java.class.path").split(java.io.File.pathSeparator)
            .map { Path.of(it) }).forEachIndexed { index, path ->
            if (Files.isDirectory(path)) {
                Files.walk(path).use { files ->
                    files.filter { Files.isRegularFile(it) }.forEach {
                        runtimeFiles += "$index/${path.relativize(it).toString().replace('\\', '/')}" to it
                    }
                }
            } else if (Files.isRegularFile(path)) {
                runtimeFiles += "$index/${path.fileName}" to path
            } else if (Files.notExists(path)) {
                // Gradle includes output directories for source sets with no Java/resources.
                // The JVM skips these. Preserve their absent slots so newly appearing inputs differ.
                absentRuntimeEntries.add(index)
            } else {
                error("Unreadable runtime classpath entry")
            }
        }
        val runtimeHash = LocalBaselineCapture.digest(
            (hashEntries(runtimeFiles) + ":absent=" + absentRuntimeEntries.joinToString(",")).toByteArray(Charsets.UTF_8))
        return LocalBaselineIdentity(revision, sourceHash, runtimeHash)
    }

    /** Windows JavaExec can replace the whole classpath with a randomly named manifest-only JAR. */
    internal fun effectiveClasspath(entries: List<Path>): List<Path> {
        val visited = linkedSetOf<Path>()
        val resolved = mutableListOf<Path>()
        fun visit(entry: Path) {
            val path = entry.toAbsolutePath().normalize()
            if (!visited.add(path)) return
            if (Files.isRegularFile(path) && path.fileName.toString().endsWith(".jar")) {
                JarFile(path.toFile()).use { jar ->
                    val manifest = jar.manifest
                    val dependencies = manifest?.mainAttributes?.getValue("Class-Path")
                    val carrier = !dependencies.isNullOrBlank() && manifest.entries.isEmpty() &&
                        manifest.mainAttributes.keys.all { it.toString() in setOf("Manifest-Version", "Class-Path") } &&
                        jar.entries().asSequence().all { it.isDirectory || it.name == "META-INF/MANIFEST.MF" }
                    if (!carrier) resolved.add(path)
                    dependencies?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.forEach { dependency ->
                        val uri = path.toUri().resolve(dependency)
                        require(uri.scheme == "file") { "Non-local manifest classpath is unsupported" }
                        visit(Path.of(uri))
                    }
                }
            } else {
                resolved.add(path)
            }
        }
        entries.forEach { visit(it) }
        return resolved
    }

    private fun hashEntries(entries: List<Pair<String, Path>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.sortedBy { it.first }.forEach { (name, path) ->
            digest.update(name.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            val fileDigest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    fileDigest.update(buffer, 0, count)
                }
            }
            digest.update(fileDigest.digest())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

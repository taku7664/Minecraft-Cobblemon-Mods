package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.net.JarURLConnection
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** Test-only scripted referee. No runtime registrations, server, network or AI decision owner. */
internal object EmbeddedShowdownOracle {
    fun entryPath(root: Path, name: String): Path {
        val normalized = root.toAbsolutePath().normalize()
        require(!name.contains('\\') && !name.contains(':')) { "Invalid archive path" }
        val target = normalized.resolve(name).normalize()
        require(target.startsWith(normalized) && target != normalized) { "Archive path escapes output" }
        return target
    }

    fun run(directory: Path, hiddenVariant: Boolean = false): JsonObject =
        runScript(directory, "/oracle/embedded-showdown.cjs", hiddenVariant.toString())

    fun damage(directory: Path): JsonObject = runScript(directory, "/oracle/damage-oracle.cjs", "")

    fun turnOrder(directory: Path): JsonObject = runScript(directory, "/oracle/turn-order-oracle.cjs", "")

    fun statusResidual(directory: Path): JsonObject = runScript(directory, "/oracle/status-residual-oracle.cjs", "")

    fun passiveResidual(directory: Path): JsonObject = runScript(directory, "/oracle/passive-residual-oracle.cjs", "")

    fun weatherResidual(directory: Path): JsonObject = runScript(directory, "/oracle/weather-residual-oracle.cjs", "")

    fun typeChanges(directory: Path): JsonObject = runScript(directory, "/oracle/type-change-oracle.cjs", "")

    fun substituteLifecycle(directory: Path): JsonObject = runScript(directory, "/oracle/substitute-lifecycle.cjs", "")

    fun doublesRetarget(directory: Path): JsonObject = runScript(directory, "/oracle/doubles-retarget.cjs", "")

    fun ppTimeline(directory: Path): JsonObject = runScript(directory, "/oracle/pp-timeline.cjs", "")

    fun pressurePp(directory: Path): JsonObject = runScript(directory, "/oracle/pressure-pp.cjs", "")

    fun presetAudit(directory: Path, input: JsonObject): JsonObject {
        Files.createDirectories(directory)
        val inputFile = directory.resolve("preset-input.json").toAbsolutePath()
        Files.writeString(inputFile, input.toString(), CREATE_NEW)
        return runScript(directory, "/oracle/preset-audit.cjs", inputFile.toString())
    }

    fun aiDecisionReplay(directory: Path, choices: List<String>, hiddenVariant: Boolean = false): JsonObject =
        runScript(directory, "/oracle/ai-decision-replay.cjs", java.util.Base64.getEncoder().encodeToString(
            GsonBuilder().create().toJson(mapOf("choices" to choices, "hiddenVariant" to hiddenVariant))
                .toByteArray(Charsets.UTF_8)))

    private fun runScript(directory: Path, scriptResource: String, argument: String): JsonObject {
        Files.createDirectories(directory)
        val engine = Files.createDirectory(directory.resolve("engine")).toAbsolutePath()
        val resource = requireNotNull(javaClass.getResource("/data/cobblemon/showdown.zip")) {
            "Cobblemon embedded Showdown is missing from the test runtime"
        }
        val bytes = resource.openStream().use { it.readBytes() }
        var extracted = 0L
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = entryPath(engine, entry.name)
                if (!entry.isDirectory && (entry.name.endsWith(".js") || entry.name.endsWith(".json"))) {
                    Files.createDirectories(target.parent)
                    Files.newOutputStream(target, CREATE_NEW).use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            extracted += count
                            require(extracted <= 128L * 1024 * 1024) { "Embedded archive exceeds extraction limit" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        val scriptBytes = requireNotNull(javaClass.getResourceAsStream(scriptResource)).use { it.readBytes() }
        val script = directory.resolve("oracle.cjs").toAbsolutePath()
        Files.write(script, scriptBytes, CREATE_NEW)
        val stdout = directory.resolve("referee-output.json").toAbsolutePath()
        val stderr = directory.resolve("stderr.txt").toAbsolutePath()
        val builder = ProcessBuilder("node", "--unhandled-rejections=strict", script.toString(), engine.toString(), argument)
            .directory(directory.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile())
        builder.environment().remove("NODE_OPTIONS")
        builder.environment().remove("NODE_PATH")
        val process = builder.start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            error("Embedded Showdown exceeded 30 seconds")
        }
        check(process.exitValue() == 0) { "Embedded Showdown failed: ${Files.readString(stderr).take(3000)}" }
        val result = JsonParser.parseString(Files.readString(stdout)).asJsonObject
        result.addProperty("engineSha256", LocalBaselineCapture.digest(bytes))
        result.addProperty("adapterSha256", LocalBaselineCapture.digest(scriptBytes))
        val connection = resource.openConnection()
        if (connection is JarURLConnection) {
            val jar = connection.jarFile
            val metadata = jar.getEntry("fabric.mod.json")
            if (metadata != null) {
                val version = jar.getInputStream(metadata).reader().use { JsonParser.parseReader(it).asJsonObject["version"].asString }
                result.addProperty("cobblemonArtifactVersion", version)
            }
        }
        result.addProperty("evidence", "INDEPENDENT_SCRIPTED_ENGINE_NOT_GAMEPLAY_OR_AI_QUALITY")
        return result
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Expected a new output directory" }
        val directory = Path.of(args[0]).toAbsolutePath().normalize()
        Files.createDirectories(directory.parent)
        Files.createDirectory(directory)
        val result = run(directory)
        val publicOnly = JsonObject().apply {
            add("initialObservation", result["initialPublicObservation"])
            add("events", result["publicLog"])
        }
        Files.writeString(directory.resolve("public-observation.json"), publicOnly.toString(), CREATE_NEW)
        Files.writeString(directory.resolve("result.json"), GsonBuilder().setPrettyPrinting().create().toJson(result), CREATE_NEW)
        println("embedded oracle complete: $directory")
    }
}

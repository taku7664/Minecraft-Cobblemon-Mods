package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

/** Raw catalog accounting, not a filtered simulation roster or an AI quality measurement. */
internal object EmbeddedPresetAudit {
    fun rawSets(): JsonArray = JsonArray().apply {
        LocalTacticalSimulationRoster.rentalSetRoots().forEach { root ->
            root.getAsJsonArray("rental_sets").forEach { add(it.deepCopy()) }
        }
    }

    fun run(directory: Path, sets: JsonArray = rawSets()): JsonObject {
        val ids = sets.map { it.asJsonObject["set_id"].asString }
        require(ids.isNotEmpty() && ids.distinct().size == ids.size) { "Empty or duplicate preset IDs" }
        val input = JsonObject().apply { add("sets", sets.deepCopy()) }
        return EmbeddedShowdownOracle.presetAudit(directory, input).apply {
            check(getAsJsonArray("sets").map { it.asJsonObject["setId"].asString } == ids) {
                "Native audit lost or reordered presets"
            }
            addProperty("catalogSha256", LocalBaselineCapture.digest(input.toString().toByteArray(Charsets.UTF_8)))
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Expected a new output directory" }
        val directory = Path.of(args[0]).toAbsolutePath().normalize()
        Files.createDirectories(directory.parent)
        Files.createDirectory(directory)
        val result = run(directory)
        Files.writeString(directory.resolve("audit.json"), GsonBuilder().setPrettyPrinting().create().toJson(result), CREATE_NEW)
        println("PRESET_AUDIT total=${result["total"]} registryCompatible=${result["registryCompatible"]} obtainable=${result["obtainable"]}")
        println("PRESET_INIT initialized=${result["initialized"]} errors=${result["initializationErrors"]} speciesMismatch=${result["initialSpeciesMismatches"]} typeMismatch=${result["initialTypeMismatches"]}")
        println("audit: $directory")
    }
}

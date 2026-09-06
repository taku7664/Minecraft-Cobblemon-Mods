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

    fun run(directory: Path, sets: JsonArray = rawSets(), teamPairs: Int = 0, teamSeed: Int = 20260906,
        teamSplit: EvaluationSplit? = null): JsonObject {
        require(teamPairs in 0..1000)
        val ids = sets.map { it.asJsonObject["set_id"].asString }
        require(ids.isNotEmpty() && ids.distinct().size == ids.size) { "Empty or duplicate preset IDs" }
        val input = JsonObject().apply {
            add("sets", sets.deepCopy())
            addProperty("teamPairs", teamPairs)
            addProperty("teamSeed", teamSeed)
            addProperty("teamSplit", teamSplit?.name ?: "ALL")
            add("reservedTuningKeys", JsonArray().apply { EmbeddedNativeCorpus.reservedTuningKeys.sorted().forEach(::add) })
        }
        return EmbeddedShowdownOracle.presetAudit(directory, input).apply {
            check(getAsJsonArray("sets").map { it.asJsonObject["setId"].asString } == ids) {
                "Native audit lost or reordered presets"
            }
            getAsJsonObject("teamSampling").getAsJsonArray("pairs").forEach { value ->
                val pair = value.asJsonObject
                check(pair["corpusKey"].asString == EmbeddedNativeCorpus.key(pair)) { "Native corpus identity mismatch" }
                check(pair["partition"].asString == EmbeddedNativeCorpus.split(pair).name) { "Native corpus partition mismatch" }
                check(teamSplit == null || pair["partition"].asString == teamSplit.name) { "Native corpus crossed partition" }
            }
            val catalog = JsonObject().apply { add("sets", sets.deepCopy()) }
            addProperty("catalogSha256", LocalBaselineCapture.digest(catalog.toString().toByteArray(Charsets.UTF_8)))
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..3) { "Expected a new output directory, optional team pair count and seed" }
        val directory = Path.of(args[0]).toAbsolutePath().normalize()
        Files.createDirectories(directory.parent)
        Files.createDirectory(directory)
        val result = run(directory, teamPairs = args.getOrNull(1)?.toInt() ?: 0,
            teamSeed = args.getOrNull(2)?.toInt() ?: 20260906)
        Files.writeString(directory.resolve("audit.json"), GsonBuilder().setPrettyPrinting().create().toJson(result), CREATE_NEW)
        println("PRESET_AUDIT total=${result["total"]} registryCompatible=${result["registryCompatible"]} obtainable=${result["obtainable"]}")
        println("PRESET_INIT initialized=${result["initialized"]} errors=${result["initializationErrors"]} speciesMismatch=${result["initialSpeciesMismatches"]} typeMismatch=${result["initialTypeMismatches"]}")
        val sampling = result.getAsJsonObject("teamSampling")
        println("PRESET_TEAMS pairs=${sampling.getAsJsonArray("pairs").size()} eligible=${sampling["eligibleSets"]} seed=${sampling["seed"]}")
        println("audit: $directory")
    }
}

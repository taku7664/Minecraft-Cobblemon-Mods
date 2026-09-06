package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

/** Seat-swapped integration capture; both seats still use the same product Local Brain. */
internal object EmbeddedNativePairs {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 3..4) { "Expected new output directory, pair count, sampling seed and optional partition" }
        val count = args[1].toInt()
        require(count in 1..1000)
        val seed = args[2].toInt()
        val split = args.getOrNull(3)?.takeUnless { it == "ALL" }?.let(EvaluationSplit::valueOf)
        val directory = Path.of(args[0]).toAbsolutePath()
        Files.createDirectories(directory.parent)
        Files.createDirectory(directory)
        val audit = EmbeddedPresetAudit.run(directory.resolve("audit"), teamPairs = count, teamSeed = seed, teamSplit = split)
        Files.writeString(directory.resolve("audit.json"), audit.toString(), CREATE_NEW)
        val results = JsonArray()
        audit.getAsJsonObject("teamSampling").getAsJsonArray("pairs").forEachIndexed { index, value ->
            val result = run(directory.resolve("audit/engine"), value.asJsonObject, directory.resolve("pair-$index"))
            result.addProperty("pairDirectory", "pair-$index")
            results.add(result)
            println("NATIVE_PAIR index=$index complete=${result["complete"]} outcomes=${result["teamAOutcomes"]}")
        }
        val outcomes = results.flatMap { it.asJsonObject.getAsJsonArray("teamAOutcomes").map { value -> value.asString } }
        val summary = JsonObject().apply {
            addProperty("schemaVersion", 1); addProperty("samplingSeed", seed)
            addProperty("partition", split?.name ?: "ALL")
            addProperty("catalogSha256", audit["catalogSha256"].asString)
            add("partitionMethod", audit.getAsJsonObject("teamSampling")["partitionMethod"].deepCopy())
            addProperty("pairs", results.size()); addProperty("battles", outcomes.size)
            addProperty("completePairs", results.count { it.asJsonObject["complete"].asBoolean })
            for (kind in listOf("WIN", "LOSS", "DRAW", "INCOMPLETE")) addProperty(kind.lowercase(), outcomes.count { it == kind })
            addProperty("perspective", "ORIGINAL_P1_TEAM_A_NOT_CHALLENGER_POLICY")
            addProperty("evidence", "SAME_LOCAL_BRAIN_SEAT_SWAPPED_PARTIAL_ADAPTER_NOT_QUALITY_PROOF")
            add("results", results)
        }
        Files.writeString(directory.resolve("summary.json"), summary.toString(), CREATE_NEW)
        println("native paired capture: $directory")
    }

    fun orient(pair: JsonObject, reverse: Boolean): JsonObject = pair.deepCopy().apply {
        if (reverse) {
            add("p1", pair.getAsJsonObject("p2").deepCopy())
            add("p2", pair.getAsJsonObject("p1").deepCopy())
        }
    }

    fun outcome(result: JsonObject, reverse: Boolean): String {
        require(result.has("winner")) { "A terminal native result must explicitly report its winner or tie" }
        val status = result["status"].asString
        val winner = result["winner"]?.takeUnless { it.isJsonNull }?.asString
        require(status in setOf("COMPLETE", "TURN_LIMIT")) { "Expected a terminal native result" }
        require(winner in setOf(null, "p1", "p2")) { "Unknown winning seat" }
        if (status == "TURN_LIMIT") {
            require(winner == null) { "A turn-limited game must not have a winner" }
            return "INCOMPLETE"
        }
        return when (winner) {
            null -> "DRAW"
            if (reverse) "p2" else "p1" -> "WIN"
            else -> "LOSS"
        }
    }

    fun run(engine: Path, pair: JsonObject, directory: Path, maxTurns: Int = 200): JsonObject {
        Files.createDirectory(directory)
        val outcomes = JsonArray()
        for (reverse in listOf(false, true)) {
            val result = EmbeddedTeamBattle.run(engine, orient(pair, reverse),
                directory.resolve(if (reverse) "reversed" else "forward"), maxTurns)
            outcomes.add(outcome(result, reverse))
        }
        val result = JsonObject().apply {
            addProperty("corpusKey", EmbeddedNativeCorpus.key(pair))
            addProperty("partition", EmbeddedNativeCorpus.split(pair).name)
            add("originalTeamASetIds", pair.getAsJsonObject("p1")["setIds"].deepCopy())
            add("originalTeamBSetIds", pair.getAsJsonObject("p2")["setIds"].deepCopy())
            add("battleSeed", pair["battleSeed"].deepCopy())
            add("teamAOutcomes", outcomes)
            addProperty("complete", outcomes.none { it.asString == "INCOMPLETE" })
            addProperty("forwardResult", "forward/result.json")
            addProperty("reversedResult", "reversed/result.json")
        }
        Files.writeString(directory.resolve("pair.json"), result.toString(), CREATE_NEW)
        return result
    }
}

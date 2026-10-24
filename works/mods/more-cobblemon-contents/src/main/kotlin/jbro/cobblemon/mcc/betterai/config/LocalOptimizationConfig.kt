package jbro.cobblemon.mcc.betterai.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import jbro.cobblemon.mcc.betterai.evaluation.LocalDecisionTuning

/** Independent opt-ins. Missing files/keys never enable an approximation. */
internal data class LocalOptimizationSettings(
    val simpleFixedHpReactions: Boolean = false,
    val simplePoisonEvaluation: Boolean = false,
    val poisonEvaluationTurns: Int = 2,
    val simpleHpMatchups: Boolean = false,
    val simpleForecastMatchups: Boolean = false,
) {
    init { require(poisonEvaluationTurns in 1..8) }
    fun applyTo(tuning: LocalDecisionTuning): LocalDecisionTuning = tuning.copy(
        simpleFixedHpReactions = simpleFixedHpReactions,
        simpleHpMatchups = simpleHpMatchups,
        simpleForecastMatchups = simpleForecastMatchups,
        poisonEvaluationTurns = poisonEvaluationTurns.takeIf { simplePoisonEvaluation },
    )
}

/** A battle keeps its format's settings snapshot; changing the other format has no effect. */
internal data class LocalOptimizationConfig(
    val single: LocalOptimizationSettings = LocalOptimizationSettings(),
    val double: LocalOptimizationSettings = LocalOptimizationSettings(),
) {
    fun applyTo(tuning: LocalDecisionTuning, format: jbro.cobblemon.mcc.internal.ai.BattleFormat): LocalDecisionTuning =
        (if (format == jbro.cobblemon.mcc.internal.ai.BattleFormat.SINGLE) single else double).applyTo(tuning)
}

internal object LocalOptimizationConfigStore {
    const val FILE_NAME = "betterai-optimizations.json"
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun loadOrCreate(path: Path): LocalOptimizationConfig {
        if (!Files.exists(path)) save(path, LocalOptimizationConfig())
        val root = read(path)
        fun settings(root: JsonObject): LocalOptimizationSettings {
            fun flag(key: String): Boolean {
                val value = root.get(key) ?: return false
                require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "$key must be a boolean" }
                return value.asBoolean
            }
            val turns = root.get("poisonEvaluationTurns")?.let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asBigDecimal.stripTrailingZeros().scale() <= 0)
                it.asBigDecimal.intValueExact()
            } ?: 2
            return LocalOptimizationSettings(flag("simpleFixedHpReactions"), flag("simplePoisonEvaluation"), turns, flag("simpleHpMatchups"), flag("simpleForecastMatchups"))
        }
        // Existing flat files retain their previous meaning for both formats until explicitly separated.
        val legacy = settings(root)
        fun section(key: String): LocalOptimizationSettings = root.get(key)?.let {
            require(it.isJsonObject) { "$key must be an object" }
            settings(it.asJsonObject)
        } ?: legacy
        return LocalOptimizationConfig(section("single"), section("double"))
    }

    fun save(path: Path, config: LocalOptimizationConfig) {
        val root = if (Files.exists(path)) read(path) else JsonObject()
        fun saveSection(key: String, settings: LocalOptimizationSettings) {
            val section = root.get(key)?.let {
                require(it.isJsonObject) { "$key must be an object" }; it.asJsonObject
            } ?: JsonObject()
            section.addProperty("simpleFixedHpReactions", settings.simpleFixedHpReactions)
            section.addProperty("simplePoisonEvaluation", settings.simplePoisonEvaluation)
            section.addProperty("poisonEvaluationTurns", settings.poisonEvaluationTurns)
            section.addProperty("simpleHpMatchups", settings.simpleHpMatchups)
            section.addProperty("simpleForecastMatchups", settings.simpleForecastMatchups)
            root.add(key, section)
        }
        saveSection("single", config.single)
        saveSection("double", config.double)
        for (key in listOf("simpleFixedHpReactions", "simplePoisonEvaluation", "poisonEvaluationTurns", "simpleHpMatchups", "simpleForecastMatchups")) root.remove(key)
        Files.createDirectories(requireNotNull(path.parent))
        val temporary = path.resolveSibling("${path.fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(temporary, gson.toJson(root) + System.lineSeparator())
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun read(path: Path): JsonObject = Files.newBufferedReader(path).use { JsonParser.parseReader(it).asJsonObject }
}

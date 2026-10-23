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
internal data class LocalOptimizationConfig(
    val simpleFixedHpReactions: Boolean = false,
    val simplePoisonEvaluation: Boolean = false,
    val poisonEvaluationTurns: Int = 2,
) {
    init { require(poisonEvaluationTurns in 1..8) }
    fun applyTo(tuning: LocalDecisionTuning): LocalDecisionTuning = tuning.copy(
        simpleFixedHpReactions = simpleFixedHpReactions,
        poisonEvaluationTurns = poisonEvaluationTurns.takeIf { simplePoisonEvaluation },
    )
}

internal object LocalOptimizationConfigStore {
    const val FILE_NAME = "betterai-optimizations.json"
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun loadOrCreate(path: Path): LocalOptimizationConfig {
        if (!Files.exists(path)) save(path, LocalOptimizationConfig())
        val root = read(path)
        fun flag(key: String): Boolean {
            val value = root.get(key) ?: return false
            require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "$key must be a boolean" }
            return value.asBoolean
        }
        val turns = root.get("poisonEvaluationTurns")?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asBigDecimal.stripTrailingZeros().scale() <= 0)
            it.asBigDecimal.intValueExact()
        } ?: 2
        return LocalOptimizationConfig(flag("simpleFixedHpReactions"), flag("simplePoisonEvaluation"), turns)
    }

    fun save(path: Path, config: LocalOptimizationConfig) {
        val root = if (Files.exists(path)) read(path) else JsonObject()
        root.addProperty("simpleFixedHpReactions", config.simpleFixedHpReactions)
        root.addProperty("simplePoisonEvaluation", config.simplePoisonEvaluation)
        root.addProperty("poisonEvaluationTurns", config.poisonEvaluationTurns)
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

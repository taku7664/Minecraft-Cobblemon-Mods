package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

internal class OpenRouterDecisionSummaryRecord private constructor(
    val battleId: String,
    val turn: Int,
    val actionId: String,
    val difficulty: String,
    val summary: String,
) {
    companion object {
        fun create(
            battleId: String,
            turn: Int,
            actionId: String,
            difficulty: String,
            rawSummary: String?,
        ) = OpenRouterDecisionSummaryRecord(
            battleId = OpenRouterDecisionSummaryDiagnostics.sanitizeToken(battleId),
            turn = turn.coerceAtLeast(0),
            actionId = OpenRouterDecisionSummaryDiagnostics.sanitizeToken(actionId),
            difficulty = OpenRouterDecisionSummaryDiagnostics.sanitizeToken(difficulty),
            summary = OpenRouterDecisionSummaryDiagnostics.sanitizeSummary(rawSummary) ?: "<unavailable>",
        )
    }
}

internal fun interface OpenRouterDecisionSummarySink {
    fun append(record: OpenRouterDecisionSummaryRecord)

    companion object {
        val NONE = OpenRouterDecisionSummarySink { }
    }
}

internal class JsonlOpenRouterDecisionSummarySink(
    private val path: Path,
    private val now: () -> Instant = Instant::now,
) : OpenRouterDecisionSummarySink {
    @Synchronized
    override fun append(record: OpenRouterDecisionSummaryRecord) {
        path.parent?.let(Files::createDirectories)
        val json = JsonObject().apply {
            addProperty("timestamp", now().toString())
            addProperty("battle", record.battleId)
            addProperty("turn", record.turn)
            addProperty("actionId", record.actionId)
            addProperty("difficulty", record.difficulty)
            addProperty("summary", record.summary)
        }
        Files.writeString(
            path,
            json.toString() + System.lineSeparator(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
        )
    }
}

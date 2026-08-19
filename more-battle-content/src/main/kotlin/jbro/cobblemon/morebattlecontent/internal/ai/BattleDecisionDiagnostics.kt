package jbro.cobblemon.morebattlecontent.internal.ai

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind

/** Builds a bounded, secret-free summary for one resolved Brain decision. */
internal object BattleDecisionDiagnostics {
    fun summary(
        source: BattleDecisionSource,
        candidateCount: Int,
        elapsedMillis: Long,
        actionKinds: List<BattleActionKind> = emptyList(),
        failures: List<BattleDecisionFailure>,
    ): String {
        require(candidateCount >= 0)
        require(elapsedMillis >= 0L)
        val failureSummary = failures.joinToString(separator = ",") { failure ->
            "${failure.stage.name}:${failure.reason.name}"
        }.ifEmpty { "none" }
        val actionSummary = actionKinds.joinToString(separator = "+") { it.name }
            .takeIf { it.isNotEmpty() }
            ?.let { " action_kinds=$it" }
            .orEmpty()
        return "source=${source.name} candidate_count=$candidateCount elapsed_ms=$elapsedMillis" +
            "$actionSummary failures=$failureSummary"
    }
}

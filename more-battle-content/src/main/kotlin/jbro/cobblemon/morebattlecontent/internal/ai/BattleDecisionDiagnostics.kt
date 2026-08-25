package jbro.cobblemon.morebattlecontent.internal.ai

import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind

/** Builds a bounded, secret-free summary for one resolved Brain decision. */
internal object BattleDecisionDiagnostics {
    fun summary(
        source: BattleDecisionSource,
        candidateCount: Int,
        elapsedMillis: Long,
        actionKinds: List<BattleActionKind> = emptyList(),
        diagnosticTags: Set<String> = emptySet(),
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
        val diagnosticsSummary = diagnosticTags.asSequence()
            .filter(::isPublicOperationalDiagnostic)
            .sorted()
            .take(MAX_DIAGNOSTIC_TAGS)
            .joinToString(",")
            .takeIf(String::isNotEmpty)
            ?.let { " diagnostics=$it" }
            .orEmpty()
        return "source=${source.name} candidate_count=$candidateCount elapsed_ms=$elapsedMillis" +
            "$actionSummary$diagnosticsSummary failures=$failureSummary"
    }

    private fun isPublicOperationalDiagnostic(tag: String): Boolean =
        tag == "lookahead_truncated" || tag == "lookahead_public_response_incomplete" ||
            PUBLIC_DIAGNOSTIC_PREFIXES.any(tag::startsWith)

    private val PUBLIC_DIAGNOSTIC_PREFIXES = listOf(
        "difficulty_",
        "lookahead_turns_",
        "lookahead_requested_",
        "lookahead_nodes_",
        "lookahead_pruned_",
    )
    private const val MAX_DIAGNOSTIC_TAGS = 8
}

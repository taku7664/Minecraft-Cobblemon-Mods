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
        tag in PUBLIC_DIAGNOSTIC_TAGS || PUBLIC_DIAGNOSTIC_PREFIXES.any(tag::startsWith)

    private val PUBLIC_DIAGNOSTIC_TAGS = setOf(
        "lookahead_truncated",
        "lookahead_public_response_incomplete",
        // The trainer had a stronger attack available and played a weaker one. Everything the decision
        // saw is tagged alongside it, so the report does not depend on meeting that trainer again.
        "weaker_attack_chosen",
    )

    /**
     * What may appear in a battle log line.
     *
     * A whitelist rather than a filter of secrets, so a tag has to be named here before it is ever
     * written. That is the right default and it is also a trap: a diagnostic added for an
     * investigation looks like it is working, produces nothing in the log, and the absence reads as
     * "the condition never happened" rather than "the tag was dropped". Anything added to the brain
     * for a live investigation has to be added here in the same change.
     */
    private val PUBLIC_DIAGNOSTIC_PREFIXES = listOf(
        "difficulty_",
        "lookahead_turns_",
        "lookahead_requested_",
        "lookahead_nodes_",
        "lookahead_pruned_",
        // What the trainer could see, and what it made of each option it had.
        "opponent_",
        "chose_",
        "cand_",
    )

    /**
     * Enough room for a full move set to be described when a choice is questioned.
     *
     * The ordinary line uses six or seven. The weaker-attack dump adds one per candidate, and a
     * truncated dump answers the wrong question - it would show some options and leave the one that
     * mattered unexplained.
     */
    private const val MAX_DIAGNOSTIC_TAGS = 28
}

package jbro.cobblemon.morebattlecontent.betterai.router

/** Keeps model-authored decision summaries bounded and safe for one-line server logs. */
internal object OpenRouterDecisionSummaryDiagnostics {
    private const val MAXIMUM_SUMMARY_LENGTH = 240

    fun format(
        battleId: String,
        turn: Int,
        actionId: String,
        difficulty: String,
        rawSummary: String?,
    ): String =
        "battle=${sanitizeToken(battleId)} turn=${turn.coerceAtLeast(0)} " +
            "action_id=${sanitizeToken(actionId)} difficulty=${sanitizeToken(difficulty)} " +
            "summary=${sanitizeSummary(rawSummary) ?: "<unavailable>"}"

    fun sanitizeSummary(rawSummary: String?): String? = rawSummary
        ?.map { character ->
            if (character.isISOControl() || Character.getType(character) == Character.FORMAT.toInt()) ' ' else character
        }
        ?.joinToString(separator = "")
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(MAXIMUM_SUMMARY_LENGTH)
        ?.trim()
        ?.takeIf(String::isNotBlank)

    fun sanitizeToken(raw: String): String = raw
        .replace(Regex("[^\\p{L}\\p{N}_.:-]+"), "_")
        .take(96)
        .ifBlank { "unknown" }
}

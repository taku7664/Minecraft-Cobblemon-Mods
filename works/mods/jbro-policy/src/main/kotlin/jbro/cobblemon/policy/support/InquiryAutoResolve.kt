package jbro.cobblemon.policy.support

/** Only a supported, read-only answer may close an inquiry without an operator. */
internal object InquiryAutoResolve {
    private fun say(key: String) = PichuRules.current().text("texts.$key")

    /** Something broke or went missing: no rule quotation answers this. */
    private val damageReport get() = PichuRules.current().regex("patterns.damageReport")
    /** "It won't work" is often a usage question; the wiki may explain it, on the condition below. */
    private val failureReport get() = PichuRules.current().regex("patterns.failureReport")

    /** Whether an answer to [reason] is a first try: the inquiry stays open, and the player's follow-up reaches an operator. */
    fun tentative(reason: String) = failureReport.containsMatchIn(reason)

    /** [strict] once the player said the answer did not help: then only damage-free usage questions may still close. */
    fun guard(reason: String, verdict: InquiryVerdict, strict: Boolean = false): InquiryVerdict {
        if (verdict.category != InquiryVerdict.Category.QUESTION) return verdict
        val refused = damageReport.containsMatchIn(reason) || (strict && failureReport.containsMatchIn(reason))
        if (!refused) return verdict
        return verdict.copy(
            category = InquiryVerdict.Category.OTHER,
            verdict = InquiryVerdict.Verdict.UNKNOWN,
            resolution = InquiryVerdict.Resolution.OPERATOR,
            operatorDetail = say("guardOperator") + "\n\n" + verdict.operatorDetail,
            playerSummary = say("guardPlayer"),
        )
    }

    sealed interface Plan {
        data object Explain : Plan
        data class HandOver(val why: String) : Plan
    }

    fun plan(verdict: InquiryVerdict, reference: String): Plan {
        if (verdict.autoCommands.isNotEmpty()) return Plan.HandOver(say("handOverAutoCommands"))
        if (verdict.category != InquiryVerdict.Category.QUESTION) {
            return Plan.HandOver(say("handOverKind"))
        }
        if (verdict.resolution != InquiryVerdict.Resolution.AUTO || verdict.verdict != InquiryVerdict.Verdict.MATCH) {
            return Plan.HandOver(say("handOverUnsure"))
        }
        if (verdict.playerSummary.isBlank() || verdict.suggestedActions.isNotEmpty() || verdict.suggestedCommands.isNotEmpty()) {
            return Plan.HandOver(say("handOverRemaining"))
        }
        // The server's own notes about what it looked up are not evidence, so lines that start or say them never count.
        val rules = PichuRules.current()
        val notes = listOf(rules.text("ai.wikiPages"), rules.text("ai.referencesHeader")).map { it.substringBefore('{').trim() }.filter { it.isNotEmpty() }
        val unverified = rules.list("inquiry.unverifiedMarkers")
        if (verdict.evidence.isEmpty() || !verdict.evidence.all { evidence ->
                val quote = evidence.trim()
                quote.length >= 12 && reference.lineSequence().any { line ->
                    line.contains(quote) && notes.none { line.startsWith(it) } && unverified.none { line.contains(it) }
                }
            }) {
            return Plan.HandOver(say("handOverNoEvidence"))
        }
        return Plan.Explain
    }
}

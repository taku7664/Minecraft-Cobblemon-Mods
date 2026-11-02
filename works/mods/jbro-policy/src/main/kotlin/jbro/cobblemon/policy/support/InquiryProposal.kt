package jbro.cobblemon.policy.support

/**
 * What Pichu asks an operator to approve: the review's suggested commands an approval can run as they stand. The review
 * reads player text, so its commands stay within a short list of roots and aimed at the inquirer; anything else is only
 * shown for an operator to copy.
 */
internal object InquiryProposal {
    private val ROOTS = setOf("bp", "legends", "give", "spawnpokemonfor")
    private val UNFILLED = Regex("[<>;]")

    fun executable(commands: List<InquiryVerdict.SuggestedCommand>, accountName: String) = commands.filter { suggestion ->
        val words = suggestion.command.removePrefix("/").trim().split(Regex("\\s+"))
        !UNFILLED.containsMatchIn(suggestion.command) && words.first().lowercase() in ROOTS && accountName in words.drop(1)
    }

    fun question(commands: List<InquiryVerdict.SuggestedCommand>) = buildString {
        append("이렇게 처리하면 좋다고 생각해요. 이래도 될까요?")
        for (command in commands) {
            append("\n`").append(command.command).append('`')
            if (command.why.isNotBlank()) append(" · ").append(command.why)
        }
    }
}

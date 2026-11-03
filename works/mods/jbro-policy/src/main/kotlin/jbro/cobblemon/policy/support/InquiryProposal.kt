package jbro.cobblemon.policy.support

/**
 * What Pichu asks an operator to approve: the review's suggested commands an approval can run as they stand. The review
 * reads player text, so its commands stay within the rules' `inquiry.approvalCommands` and aimed at the inquirer; anything
 * else is only shown for an operator to copy.
 */
internal object InquiryProposal {
    private val UNFILLED = Regex("[<>;]")

    fun executable(commands: List<InquiryVerdict.SuggestedCommand>, accountName: String): List<InquiryVerdict.SuggestedCommand> {
        val allowed = PichuRules.current().list("inquiry.approvalCommands").map(::words)
        return commands.filter { suggestion ->
            val words = words(suggestion.command.removePrefix("/"))
            !UNFILLED.containsMatchIn(suggestion.command) && accountName in words.drop(1) && allowed.any { prefix ->
                prefix.isNotEmpty() && words.size >= prefix.size && prefix.indices.all { words[it].equals(prefix[it], ignoreCase = true) }
            }
        }
    }

    private fun words(text: String) = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun question(commands: List<InquiryVerdict.SuggestedCommand>) = buildString {
        append(PichuRules.current().text("texts.proposalQuestion"))
        for (command in commands) {
            append("\n`").append(command.command).append('`')
            if (command.why.isNotBlank()) append(" · ").append(command.why)
        }
    }
}

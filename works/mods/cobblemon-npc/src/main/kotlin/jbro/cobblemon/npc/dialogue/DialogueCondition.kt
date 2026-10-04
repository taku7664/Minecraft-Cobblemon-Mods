package jbro.cobblemon.npc.dialogue

/** What a condition asks the player's side of the game. */
interface ConditionContext {
    fun hasTag(tag: String): Boolean
    fun hasPermission(level: Int): Boolean

    /** Whether [command] succeeds with a positive result, run silently as the player (`execute if ...`). */
    fun check(command: String): Boolean
}

/**
 * A condition written as text, terms joined by `&&`, each optionally negated with `!`:
 * `tag:<name>`, `perm:<level>` or `cmd:<command>`.
 */
sealed interface DialogueCondition {
    fun test(context: ConditionContext): Boolean

    data class Tag(val tag: String) : DialogueCondition {
        override fun test(context: ConditionContext) = context.hasTag(tag)
    }

    data class Permission(val level: Int) : DialogueCondition {
        override fun test(context: ConditionContext) = context.hasPermission(level)
    }

    data class Command(val command: String) : DialogueCondition {
        override fun test(context: ConditionContext) = context.check(command)
    }

    data class Not(val condition: DialogueCondition) : DialogueCondition {
        override fun test(context: ConditionContext) = !condition.test(context)
    }

    data class All(val conditions: List<DialogueCondition>) : DialogueCondition {
        override fun test(context: ConditionContext) = conditions.all { it.test(context) }
    }

    companion object {
        /** The condition [text] states, or null with the [problems] it has. */
        fun parse(text: String, problems: MutableList<DialogueProblem> = mutableListOf()): DialogueCondition? {
            val terms = text.split("&&").map(String::trim)
            if (terms.any(String::isEmpty)) {
                problems += DialogueProblem("condition_empty", listOf(text))
                return null
            }
            val parsed = terms.map { parseTerm(it, problems) ?: return null }
            return parsed.singleOrNull() ?: All(parsed)
        }

        private fun parseTerm(term: String, problems: MutableList<DialogueProblem>): DialogueCondition? {
            if (term.startsWith("!")) return parseTerm(term.substring(1).trim(), problems)?.let(::Not)
            val kind = term.substringBefore(':', "").trim().lowercase()
            val value = term.substringAfter(':', "").trim()
            if (value.isEmpty()) {
                problems += DialogueProblem("condition_term", listOf(term))
                return null
            }
            return when (kind) {
                "tag" -> Tag(value)
                "perm" -> value.toIntOrNull()?.takeIf { it in 0..4 }?.let(::Permission)
                    ?: null.also { problems += DialogueProblem("condition_permission", listOf(value)) }
                "cmd" -> Command(value.removePrefix("/"))
                else -> null.also { problems += DialogueProblem("condition_term", listOf(term)) }
            }
        }
    }
}

/** A node command: [command] without its slash, run with operator rights when [asServer]. */
data class DialogueCommand(val command: String, val asServer: Boolean) {
    companion object {
        const val SERVER_PREFIX = "@server"

        fun parse(text: String): DialogueCommand? {
            val trimmed = text.trim()
            val asServer = trimmed.startsWith(SERVER_PREFIX)
            val command = trimmed.removePrefix(SERVER_PREFIX).trim().removePrefix("/").trim()
            return if (command.isEmpty()) null else DialogueCommand(command, asServer)
        }
    }
}

/** Fills `{player}` and `{npc}` into a line or command. */
object DialogueText {
    fun fill(text: String, player: String, npc: String): String =
        text.replace("{player}", player).replace("{npc}", npc)
}

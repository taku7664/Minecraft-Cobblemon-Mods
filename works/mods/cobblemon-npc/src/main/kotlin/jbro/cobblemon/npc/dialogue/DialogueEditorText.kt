package jbro.cobblemon.npc.dialogue

/**
 * The text fields of the in-game editor and the node parts they stand for, one entry per line:
 * lines as they are, choices as `text -> node ? condition`, branches as `condition -> node`.
 */
object DialogueEditorText {
    private const val ARROW = "->"

    fun lines(text: String): List<String> = text.lines().map(String::trim).filter(String::isNotEmpty)

    fun choices(text: String): List<DialogueChoice> = lines(text).map { line ->
        val arrow = line.lastIndexOf(ARROW)
        if (arrow < 0) return@map DialogueChoice(line, null)
        val target = line.substring(arrow + ARROW.length)
        val next = target.substringBefore('?').trim().ifEmpty { null }
        val condition = target.substringAfter('?', "").trim().ifEmpty { null }
        DialogueChoice(line.substring(0, arrow).trim(), next, condition)
    }

    fun branches(text: String): List<DialogueBranch> = lines(text).map { line ->
        val arrow = line.lastIndexOf(ARROW)
        if (arrow < 0) DialogueBranch(line, "") else DialogueBranch(line.substring(0, arrow).trim(), line.substring(arrow + ARROW.length).trim())
    }

    fun ofLines(values: List<String>) = values.joinToString("\n")

    fun ofChoices(values: List<DialogueChoice>) = values.joinToString("\n") { choice ->
        buildString {
            append(choice.text).append(" $ARROW ").append(choice.next.orEmpty())
            choice.condition?.let { append(" ? ").append(it) }
        }.trimEnd()
    }

    fun ofBranches(values: List<DialogueBranch>) = values.joinToString("\n") { "${it.condition} $ARROW ${it.next}" }

    /** A branch as the editor's row shows it: one condition of a [kind], possibly negated, and where it leads. */
    data class BranchRow(var kind: ConditionKind, var negated: Boolean, var value: String, var next: String) {
        fun toBranch() = DialogueBranch(kind.write(negated, value.trim()), next.trim())

        companion object {
            fun of(branch: DialogueBranch): BranchRow {
                val text = branch.condition.trim()
                // More than one term only fits the raw form.
                if ("&&" in text) return BranchRow(ConditionKind.RAW, false, text, branch.next)
                val negated = text.startsWith("!")
                val term = text.removePrefix("!").trim()
                val kind = ConditionKind.entries.firstOrNull { it.prefix != null && term.startsWith(it.prefix) }
                    ?: return BranchRow(ConditionKind.RAW, false, text, branch.next)
                return BranchRow(kind, negated, term.removePrefix(kind.prefix!!), branch.next)
            }
        }
    }

    enum class ConditionKind(val prefix: String?) {
        TAG("tag:"), PERMISSION("perm:"), COMMAND("cmd:"), RAW(null);

        fun write(negated: Boolean, value: String) =
            if (prefix == null) value else (if (negated) "!" else "") + prefix + value

        fun next() = entries[(ordinal + 1) % entries.size]
    }

    /** A node command as the editor's row shows it: the command without its slash, and whose rights run it. */
    data class CommandRow(var asServer: Boolean, var command: String) {
        fun write(): String = (if (asServer) "${DialogueCommand.SERVER_PREFIX} /" else "/") + command.trim().removePrefix("/")

        val blank get() = command.isBlank()

        companion object {
            fun of(text: String): CommandRow =
                DialogueCommand.parse(text)?.let { CommandRow(it.asServer, it.command) } ?: CommandRow(false, text.trim())
        }
    }

    /** [dialogue] with node [from] called [to], every reference to it following. */
    fun rename(dialogue: NpcDialogue, from: String, to: String): NpcDialogue {
        if (from == to || from !in dialogue.nodes || to in dialogue.nodes) return dialogue
        fun ref(id: String?) = if (id == from) to else id
        val nodes = linkedMapOf<String, DialogueNode>()
        dialogue.nodes.forEach { (id, node) ->
            nodes[if (id == from) to else id] = node.copy(
                next = ref(node.next),
                choices = node.choices.map { it.copy(next = ref(it.next)) },
                branches = node.branches.map { it.copy(next = ref(it.next)!!) },
            )
        }
        return dialogue.copy(start = ref(dialogue.start)!!, nodes = nodes)
    }
}

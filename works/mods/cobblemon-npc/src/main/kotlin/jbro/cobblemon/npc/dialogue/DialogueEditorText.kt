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

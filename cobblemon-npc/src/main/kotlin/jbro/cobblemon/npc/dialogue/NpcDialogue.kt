package jbro.cobblemon.npc.dialogue

/**
 * A conversation an NPC (or a command) opens: named [nodes], the first one [start], and who speaks when no NPC does.
 *
 * Reaching a node with [DialogueNode.lines] shows those lines one page at a time. When the last one has been read,
 * the player picks one of the node's choices, or else the node moves on by itself: to the first branch whose
 * condition holds, otherwise to [DialogueNode.next]. A node's commands run as it is left. A node without lines is
 * passed straight through, so a node of commands only is an action in the middle of the talk.
 */
data class NpcDialogue(
    val start: String,
    val nodes: Map<String, DialogueNode>,
    /** The speaker shown when no NPC opened the dialogue, or the NPC has no name. */
    val speaker: String? = null,
    /** The player skin drawn for [speaker] when no NPC opened the dialogue. */
    val skin: String? = null,
)

data class DialogueNode(
    val lines: List<String> = emptyList(),
    val choices: List<DialogueChoice> = emptyList(),
    val branches: List<DialogueBranch> = emptyList(),
    val next: String? = null,
    /** Run as the node is left: `/command` as the player, `@server /command` as the player with operator rights. */
    val commands: List<String> = emptyList(),
)

/** An answer the player can pick; hidden while [condition] does not hold. A null [next] ends the talk. */
data class DialogueChoice(val text: String, val next: String?, val condition: String? = null)

/** Moves on to [next] when [condition] holds. */
data class DialogueBranch(val condition: String, val next: String)

/** A problem found in a dialogue: a translation key under `cobblemon_npc.problem.` and its arguments. */
data class DialogueProblem(val key: String, val args: List<String> = emptyList()) {
    override fun toString() = if (args.isEmpty()) key else "$key ${args.joinToString()}"
}

object DialogueIds {
    private val DIALOGUE = Regex("[a-z0-9_.-]{1,64}")
    private val NODE = Regex("[\\p{L}\\p{N}_.-]{1,64}")

    /** Dialogue ids name their files, so they stay lower-case ASCII. */
    fun isDialogueId(id: String) = DIALOGUE.matches(id)

    fun isNodeId(id: String) = NODE.matches(id)
}

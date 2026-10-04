package jbro.cobblemon.npc.dialogue

/**
 * Walks a dialogue for one player. Every move returns what to show next and the commands the nodes left on the way
 * asked for, in order; the caller shows first and runs the commands after, so a command that opens a screen (a
 * terminal, a shop) lands over a closed or updated dialogue box.
 */
class DialogueWalker(private val dialogue: NpcDialogue, private val context: ConditionContext) {
    sealed interface Step {
        /** Shows [nodeId]'s lines; [choices] are the indices of the node's choices the player may pick. */
        data class Show(val nodeId: String, val node: DialogueNode, val choices: List<Int>) : Step
        data object End : Step
    }

    data class Move(val step: Step, val commands: List<String>)

    /** Enters [nodeId], or the start node when it is null. */
    fun enter(nodeId: String? = null): Move {
        val commands = mutableListOf<String>()
        return Move(walk(nodeId ?: dialogue.start, commands), commands)
    }

    /** The lines of [nodeId] have been read and it offers no choice. */
    fun read(nodeId: String): Move {
        val node = dialogue.nodes[nodeId] ?: return Move(Step.End, emptyList())
        val commands = node.commands.toMutableList()
        return Move(walk(route(node), commands), commands)
    }

    /** The player picked choice [index] (an index into the node's choices) of [nodeId]. */
    fun choose(nodeId: String, index: Int): Move? {
        val node = dialogue.nodes[nodeId] ?: return null
        val choice = node.choices.getOrNull(index) ?: return null
        if (!visible(choice)) return null
        val commands = node.commands.toMutableList()
        return Move(walk(choice.next, commands), commands)
    }

    private fun walk(start: String?, commands: MutableList<String>): Step {
        var id = start
        // A loop of nodes without lines would never stop; a talk that long is a mistake.
        repeat(MAX_PASSES) {
            val node = id?.let(dialogue.nodes::get) ?: return Step.End
            if (node.lines.isNotEmpty()) {
                return Step.Show(id!!, node, node.choices.indices.filter { visible(node.choices[it]) })
            }
            commands += node.commands
            id = route(node)
        }
        return Step.End
    }

    private fun route(node: DialogueNode): String? =
        node.branches.firstOrNull { holds(it.condition) }?.next ?: node.next

    private fun visible(choice: DialogueChoice) = choice.condition == null || holds(choice.condition)

    private fun holds(condition: String) = DialogueCondition.parse(condition)?.test(context) ?: false

    companion object {
        const val MAX_PASSES = 32
    }
}

package jbro.cobblemon.npc.dialogue

/** A local inspection cursor. It never evaluates conditions or dispatches commands. */
class DialoguePreviewCursor(val dialogue: NpcDialogue, start: String) {
    var nodeId: String? = start.takeIf { it in dialogue.nodes }
        private set
    var page = 0
        private set
    val node get() = nodeId?.let(dialogue.nodes::get)
    val hasMorePages get() = page + 1 < (node?.lines?.size ?: 0)
    fun advancePage() { if (hasMorePages) page++ }
    fun enter(next: String?) { nodeId = next?.takeIf { it in dialogue.nodes }; page = 0 }
}

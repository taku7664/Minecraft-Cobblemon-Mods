package jbro.cobblemon.npc.dialogue

/** An editor draft with bounded, coalesced undo history; server saves acknowledge a particular snapshot. */
class DialogueDraft(initial: NpcDialogue) {
    var value = initial
        private set
    private var saved = initial
    private val past = ArrayDeque<NpcDialogue>()
    private val future = ArrayDeque<NpcDialogue>()
    private var editingGroup: String? = null

    val dirty get() = value != saved
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()

    fun checkpoint() { editingGroup = null }

    fun replace(next: NpcDialogue, group: String? = null) {
        if (next == value) return
        if (group == null || editingGroup != group) {
            past.addLast(value)
            if (past.size > 100) past.removeFirst()
        }
        value = next
        editingGroup = group
        future.clear()
    }

    fun editNode(id: String, group: String? = null, edit: (DialogueNode) -> DialogueNode) {
        val node = value.nodes[id] ?: return
        replace(value.copy(nodes = LinkedHashMap(value.nodes).also { it[id] = edit(node) }), group)
    }

    fun markSaved(snapshot: NpcDialogue) { saved = snapshot; checkpoint() }

    fun undo(): Boolean {
        if (!canUndo) return false
        future.addLast(value)
        value = past.removeLast()
        checkpoint()
        return true
    }

    fun redo(): Boolean {
        if (!canRedo) return false
        past.addLast(value)
        value = future.removeLast()
        checkpoint()
        return true
    }

    fun renameNode(from: String, to: String): Boolean {
        if (!DialogueIds.isNodeId(to) || from !in value.nodes || (to != from && to in value.nodes)) return false
        replace(DialogueEditorText.rename(value, from, to))
        return true
    }

    fun referencesTo(id: String) = value.nodes.values.sumOf { node ->
        (if (node.next == id) 1 else 0) + node.choices.count { it.next == id } + node.branches.count { it.next == id }
    }

    /** Confirmed deletion ends incoming choices/next links and removes incoming conditional branches. */
    fun removeNode(id: String): Boolean {
        if (value.nodes.size <= 1 || id !in value.nodes) return false
        val nodes = linkedMapOf<String, DialogueNode>()
        value.nodes.filterKeys { it != id }.forEach { (key, node) ->
            nodes[key] = node.copy(next = node.next.takeUnless { it == id },
                choices = node.choices.map { if (it.next == id) it.copy(next = null) else it },
                branches = node.branches.filterNot { it.next == id })
        }
        replace(value.copy(nodes = nodes, start = value.start.takeIf { it in nodes } ?: nodes.keys.first()))
        return true
    }
}

package jbro.cobblemon.npc.dialogue

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DialogueDraftTest {
    @Test fun `page and choice validation respects the existing network boundary`() {
        fun content(count: Int) = NpcDialogue("a", mapOf("a" to DialogueNode(lines = List(count) { "Page" },
            choices = List(count) { DialogueChoice("Choice", null) })))
        assertTrue(DialogueCodec.validate(content(64)).isEmpty())
        assertEquals(listOf("page_limit", "choice_limit"), DialogueCodec.validate(content(65)).map { it.key })
    }
    @Test fun `validation points to the exact choice and branch without changing existing errors`() {
        val dialogue = original().copy(nodes = linkedMapOf("hello" to DialogueNode(lines = listOf("Hi"),
            choices = listOf(DialogueChoice("OK", null), DialogueChoice("VIP", "missing", "perm:9")),
            branches = listOf(DialogueBranch("wrong:condition", "hello")))))
        val issues = DialogueCodec.validateLocated(dialogue)
        assertEquals(DialogueCodec.validate(dialogue), issues.map { it.problem })
        assertEquals(listOf("next", "condition_permission", "condition_term"), issues.map { it.problem.key })
        assertEquals(listOf(1, 1, 0), issues.map { it.index })
        assertTrue(issues.all { it.node == "hello" })
        assertEquals(DialogueIssue.Part.BRANCH, issues.last().part)
    }
    @Test fun `preview navigates pages without evaluating dangerous conditions or commands`() {
        val dialogue = NpcDialogue("a", linkedMapOf("a" to DialogueNode(lines = listOf("one", "two"),
            branches = listOf(DialogueBranch("cmd:kill @s", "a")), commands = listOf("@server /kill @s"))))
        val cursor = DialoguePreviewCursor(dialogue, "a")
        assertTrue(cursor.hasMorePages)
        cursor.advancePage()
        assertEquals(1, cursor.page)
        assertFalse(cursor.hasMorePages)
        cursor.enter("a")
        assertEquals(0, cursor.page)
        cursor.enter("missing")
        assertNull(cursor.node)
        assertEquals(listOf("@server /kill @s"), dialogue.nodes.getValue("a").commands)
    }
    private fun original() = NpcDialogue("hello", linkedMapOf(
        "hello" to DialogueNode(lines = listOf("Hello\n{player}"), next = "bye",
            choices = listOf(DialogueChoice("Go", "bye", "tag:vip")),
            branches = listOf(DialogueBranch("perm:2", "bye"))),
        "bye" to DialogueNode(lines = listOf("Bye"), commands = listOf("@server /give @s apple")),
    ), speaker = "Guide", skin = "Steve")

    @Test fun `typing is one undo step and keeps multiline pages`() {
        val draft = DialogueDraft(original())
        draft.editNode("hello", "page") { it.copy(lines = listOf("H")) }
        draft.editNode("hello", "page") { it.copy(lines = listOf("Hi\nthere")) }
        assertTrue(draft.dirty)
        assertTrue(draft.undo())
        assertEquals(original(), draft.value)
        assertFalse(draft.dirty)
        assertTrue(draft.redo())
        assertEquals(listOf("Hi\nthere"), draft.value.nodes.getValue("hello").lines)
        assertEquals(draft.value, DialogueCodec.decode(DialogueCodec.encode(draft.value)).valid)
    }

    @Test fun `save acknowledgement only marks the submitted snapshot saved`() {
        val draft = DialogueDraft(original())
        draft.editNode("hello") { it.copy(lines = listOf("First")) }
        val submitted = draft.value
        draft.editNode("hello") { it.copy(lines = listOf("Newer")) }
        draft.markSaved(submitted)
        assertTrue(draft.dirty)
        draft.undo()
        assertFalse(draft.dirty)
    }

    @Test fun `deletion detaches every incoming reference and undo restores all data`() {
        val draft = DialogueDraft(original())
        assertEquals(3, draft.referencesTo("bye"))
        assertTrue(draft.removeNode("bye"))
        val node = draft.value.nodes.getValue("hello")
        assertNull(node.next)
        assertNull(node.choices.single().next)
        assertEquals("tag:vip", node.choices.single().condition)
        assertTrue(node.branches.isEmpty())
        assertTrue(DialogueCodec.validate(draft.value).isEmpty())
        assertTrue(draft.undo())
        assertEquals(original(), draft.value)
        assertFalse(draft.removeNode("missing"))
    }

    @Test fun `renaming is atomic and invalid names leave the draft untouched`() {
        val draft = DialogueDraft(original())
        assertFalse(draft.renameNode("bye", "hello"))
        assertFalse(draft.renameNode("bye", "bad name"))
        assertFalse(draft.canUndo)
        assertTrue(draft.renameNode("bye", "끝"))
        assertEquals("끝", draft.value.nodes.getValue("hello").choices.single().next)
        assertTrue(draft.undo())
        assertEquals(original(), draft.value)
    }

    @Test fun `last node cannot be removed and a new edit invalidates redo`() {
        val draft = DialogueDraft(original())
        draft.removeNode("hello")
        assertEquals("bye", draft.value.start)
        assertFalse(draft.removeNode("bye"))
        draft.undo()
        draft.editNode("hello") { it.copy(next = null) }
        assertFalse(draft.canRedo)
        assertEquals(original().nodes.getValue("bye"), draft.value.nodes.getValue("bye"))
    }
}

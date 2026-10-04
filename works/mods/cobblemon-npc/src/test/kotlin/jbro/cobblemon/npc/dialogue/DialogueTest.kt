package jbro.cobblemon.npc.dialogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DialogueTest {
    private class Player(val tags: Set<String> = emptySet(), val level: Int = 0, val passing: Set<String> = emptySet()) : ConditionContext {
        val checked = mutableListOf<String>()
        override fun hasTag(tag: String) = tag in tags
        override fun hasPermission(level: Int) = this.level >= level
        override fun check(command: String) = (command in passing).also { checked += command }
    }

    private fun example(): NpcDialogue {
        val json = javaClass.getResourceAsStream("/cobblemon_npc/examples/tower_guide.json")!!.readBytes().toString(Charsets.UTF_8)
        val decoded = DialogueCodec.decode(json)
        assertEquals(emptyList<DialogueProblem>(), decoded.problems)
        return decoded.valid!!
    }

    @Test
    fun `the bundled example reads and survives a round trip`() {
        val dialogue = example()
        assertEquals("greet", dialogue.start)
        assertEquals(dialogue, DialogueCodec.decode(DialogueCodec.encode(dialogue)).valid)
    }

    @Test
    fun `a qualified player is taken to the terminal and the command runs as the box closes`() {
        val walker = DialogueWalker(example(), Player(tags = setOf("tower_qualified")))
        val greet = walker.enter().step as DialogueWalker.Step.Show
        assertEquals(listOf(0, 1), greet.choices)
        val open = walker.choose("greet", 0)!!
        assertEquals("open", (open.step as DialogueWalker.Step.Show).nodeId)
        assertEquals(emptyList<String>(), open.commands)
        val end = walker.read("open")
        assertEquals(DialogueWalker.Step.End, end.step)
        assertEquals(listOf("/mcc terminal tower"), end.commands)
    }

    @Test
    fun `an unqualified player is told so and no command runs`() {
        val walker = DialogueWalker(example(), Player())
        val move = walker.choose("greet", 0)!!
        assertEquals("not_yet", (move.step as DialogueWalker.Step.Show).nodeId)
        assertEquals(DialogueWalker.Move(DialogueWalker.Step.End, emptyList()), walker.read("not_yet"))
    }

    @Test
    fun `command-only nodes run in passing and hidden choices cannot be picked`() {
        val dialogue = NpcDialogue("a", linkedMapOf(
            "a" to DialogueNode(commands = listOf("/one"), next = "b"),
            "b" to DialogueNode(lines = listOf("hi"), choices = listOf(
                DialogueChoice("vip", "c", "tag:vip"), DialogueChoice("anyone", null))),
            "c" to DialogueNode(lines = listOf("vip")),
        ))
        val walker = DialogueWalker(dialogue, Player())
        val first = walker.enter()
        assertEquals(listOf("/one"), first.commands)
        assertEquals(listOf(1), (first.step as DialogueWalker.Step.Show).choices)
        assertNull(walker.choose("b", 0))
        assertEquals(DialogueWalker.Step.End, walker.choose("b", 1)!!.step)
    }

    @Test
    fun `a loop of nodes without lines stops`() {
        val dialogue = NpcDialogue("a", linkedMapOf("a" to DialogueNode(next = "b"), "b" to DialogueNode(next = "a")))
        assertEquals(DialogueWalker.Step.End, DialogueWalker(dialogue, Player()).enter().step)
    }

    @Test
    fun `conditions combine, negate and check commands`() {
        val player = Player(tags = setOf("a"), level = 2, passing = setOf("execute if entity @s"))
        assertTrue(DialogueCondition.parse("tag:a && !tag:b && perm:2")!!.test(player))
        assertFalse(DialogueCondition.parse("perm:3")!!.test(player))
        assertTrue(DialogueCondition.parse("cmd:/execute if entity @s")!!.test(player))
        assertEquals(listOf("execute if entity @s"), player.checked)
        val problems = mutableListOf<DialogueProblem>()
        assertNull(DialogueCondition.parse("level:3", problems))
        assertNull(DialogueCondition.parse("tag:a &&", problems))
        assertNull(DialogueCondition.parse("perm:9", problems))
        assertEquals(listOf("condition_term", "condition_empty", "condition_permission"), problems.map { it.key })
    }

    @Test
    fun `validation names missing nodes and bad conditions`() {
        val dialogue = NpcDialogue("x", linkedMapOf("a" to DialogueNode(lines = listOf("hi"), next = "nowhere",
            branches = listOf(DialogueBranch("weird", "a")))))
        assertEquals(listOf("start", "next", "condition_term"), DialogueCodec.validate(dialogue).map { it.key })
        assertEquals("json", DialogueCodec.decode("{ nope").problems.single().key)
    }

    @Test
    fun `commands read their server prefix and slash`() {
        assertEquals(DialogueCommand("give @s apple", true), DialogueCommand.parse("@server /give @s apple"))
        assertEquals(DialogueCommand("mcc terminal tower", false), DialogueCommand.parse(" /mcc terminal tower "))
        assertNull(DialogueCommand.parse("@server /"))
        assertEquals("hi Ash, I am Oak", DialogueText.fill("hi {player}, I am {npc}", "Ash", "Oak"))
    }

    @Test
    fun `editor text turns into choices and branches and back`() {
        val choices = DialogueEditorText.choices("응, 도전할래! -> check\n아니 -> \n비밀 -> vip ? tag:vip && perm:2\n그냥 끝")
        assertEquals(listOf(
            DialogueChoice("응, 도전할래!", "check"),
            DialogueChoice("아니", null),
            DialogueChoice("비밀", "vip", "tag:vip && perm:2"),
            DialogueChoice("그냥 끝", null),
        ), choices)
        assertEquals(choices, DialogueEditorText.choices(DialogueEditorText.ofChoices(choices)))
        val branches = DialogueEditorText.branches("tag:a -> open\n\n !tag:a -> no ")
        assertEquals(listOf(DialogueBranch("tag:a", "open"), DialogueBranch("!tag:a", "no")), branches)
        assertNotNull(DialogueEditorText.ofBranches(branches))
    }

    @Test
    fun `renaming a node carries every reference`() {
        val renamed = DialogueEditorText.rename(example(), "check", "자격확인")
        assertEquals("자격확인", renamed.nodes.getValue("greet").choices[0].next)
        assertTrue("자격확인" in renamed.nodes && "check" !in renamed.nodes)
        assertEquals(emptyList<DialogueProblem>(), DialogueCodec.validate(renamed))
        assertEquals(renamed.nodes.keys.indexOf("자격확인"), example().nodes.keys.indexOf("check"))
    }
}

package jbro.cobblemon.npc.client

import jbro.cobblemon.npc.dialogue.DialogueCodec
import jbro.cobblemon.npc.dialogue.DialogueEditorText
import jbro.cobblemon.npc.dialogue.DialogueIds
import jbro.cobblemon.npc.dialogue.DialogueNode
import jbro.cobblemon.npc.dialogue.NpcDialogue
import jbro.cobblemon.npc.network.DialogueStorePayload
import jbro.cobblemon.uikit.UiButtonVariant
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.MultiLineEditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * The node editor: the dialogue's nodes on the left, the selected node's parts on the right, one entry per line.
 * Choices are written `answer -> node ? condition`, branches `condition -> node`, commands `/command` or
 * `@server /command`.
 */
class DialogueEditorScreen(private val id: String, json: String, private val parent: Screen?) :
    NpcEditorScreen(Component.translatable("screen.cobblemon_npc.dialogue_editor", id)) {
    private var dialogue: NpcDialogue
    private var selected: String
    private var listOffset = 0
    private val fields = mutableMapOf<String, EditBox>()
    private val boxes = mutableMapOf<String, MultiLineEditBox>()

    init {
        val decoded = DialogueCodec.decode(json)
        dialogue = decoded.dialogue?.takeIf { it.nodes.isNotEmpty() }
            ?: NpcDialogue("start", linkedMapOf("start" to DialogueNode(lines = listOf("..."))))
        selected = dialogue.start.takeIf { it in dialogue.nodes } ?: dialogue.nodes.keys.first()
        decoded.problems.firstOrNull()?.let { showResult(false, problemText(it.key, it.args)) }
    }

    override fun init() {
        super.init()
        fields.clear()
        boxes.clear()
        val left = MARGIN
        val top = MARGIN
        val right = width - MARGIN
        val bottom = height - MARGIN
        panel(left, top, right - left, bottom - top, title)

        // The dialogue's own speaker, for talks a command opens without an NPC.
        var y = top + 24
        val speakerLabel = Component.translatable("screen.cobblemon_npc.field.speaker")
        val skinLabel = Component.translatable("screen.cobblemon_npc.field.skin")
        label(speakerLabel, left + 10, y + 5)
        val speakerLeft = left + 14 + font.width(speakerLabel)
        fields["speaker"] = field(speakerLeft, y, 110, dialogue.speaker.orEmpty(), Component.translatable("screen.cobblemon_npc.hint.speaker"), 64)
        label(skinLabel, speakerLeft + 120, y + 5)
        fields["skin"] = field(speakerLeft + 124 + font.width(skinLabel), y, 110, dialogue.skin.orEmpty(),
            Component.translatable("screen.cobblemon_npc.hint.skin"), 128)
        y += 26

        val actionsY = bottom - 42
        nodeList(left + 10, y, actionsY - 6)

        val node = dialogue.nodes.getValue(selected)
        val areaLeft = left + 10 + LIST_WIDTH + 10
        val areaWidth = right - 10 - areaLeft
        val firstWidth = areaWidth * 3 / 5
        val secondLeft = areaLeft + firstWidth + 8
        val secondWidth = right - 10 - secondLeft
        val rest = (actionsY - 6 - y) - (LABEL + FIELD_HEIGHT + GAP) - 2 * (LABEL + GAP)

        // First column: the node's name, its lines and its answers.
        var columnY = y
        label(Component.translatable("screen.cobblemon_npc.field.node"), areaLeft, columnY)
        val startWidth = 70
        fields["node"] = field(areaLeft, columnY + LABEL, firstWidth - startWidth - 4, selected, Component.empty(), 64)
        button(areaLeft + firstWidth - startWidth, columnY + LABEL, Component.translatable(
            if (dialogue.start == selected) "screen.cobblemon_npc.is_start" else "screen.cobblemon_npc.make_start"), width = startWidth) {
            commit()
            dialogue = dialogue.copy(start = selected)
            rebuildWidgets()
        }.active = dialogue.start != selected
        columnY += LABEL + FIELD_HEIGHT + GAP
        boxes["lines"] = box(Component.translatable("screen.cobblemon_npc.field.lines"), areaLeft, columnY, firstWidth, rest * 3 / 5,
            DialogueEditorText.ofLines(node.lines))
        columnY += LABEL + rest * 3 / 5 + GAP
        boxes["choices"] = box(Component.translatable("screen.cobblemon_npc.field.choices"), areaLeft, columnY, firstWidth, rest - rest * 3 / 5,
            DialogueEditorText.ofChoices(node.choices))

        // Second column: where the node goes by itself, and what it runs on leaving.
        columnY = y
        label(Component.translatable("screen.cobblemon_npc.field.next"), secondLeft, columnY)
        fields["next"] = field(secondLeft, columnY + LABEL, secondWidth, node.next.orEmpty(), Component.translatable("screen.cobblemon_npc.hint.next"), 64)
        columnY += LABEL + FIELD_HEIGHT + GAP
        boxes["branches"] = box(Component.translatable("screen.cobblemon_npc.field.branches"), secondLeft, columnY, secondWidth, rest / 2,
            DialogueEditorText.ofBranches(node.branches))
        columnY += LABEL + rest / 2 + GAP
        boxes["commands"] = box(Component.translatable("screen.cobblemon_npc.field.commands"), secondLeft, columnY, secondWidth, rest - rest / 2,
            DialogueEditorText.ofLines(node.commands))

        val actions = listOf(
            Triple("screen.cobblemon_npc.save", UiButtonVariant.PRIMARY) { save(false) },
            Triple("screen.cobblemon_npc.save_preview", UiButtonVariant.SECONDARY) { save(true) },
            Triple("gui.done", UiButtonVariant.GHOST) { onClose() },
        )
        val actionWidth = 96
        var actionX = right - 10 - actions.size * (actionWidth + 6) + 6
        actions.forEach { (key, variant, press) ->
            button(actionX, actionsY, Component.translatable(key), variant, actionWidth, press)
            actionX += actionWidth + 6
        }
        label(Component.translatable("screen.cobblemon_npc.syntax"), left + 10, actionsY + 6)
    }

    private fun nodeList(x: Int, top: Int, bottom: Int) {
        val ids = dialogue.nodes.keys.toList()
        val rows = ((bottom - top - ROW - 4) / ROW).coerceAtLeast(1)
        listOffset = listOffset.coerceIn(0, (ids.size - rows).coerceAtLeast(0))
        ids.drop(listOffset).take(rows).forEachIndexed { index, nodeId ->
            val text = if (nodeId == dialogue.start) "★ $nodeId" else nodeId
            val variant = if (nodeId == selected) UiButtonVariant.PRIMARY else UiButtonVariant.GHOST
            button(x, top + index * ROW, Component.literal(text), variant, LIST_WIDTH) { select(nodeId) }
        }
        val half = (LIST_WIDTH - 4) / 2
        button(x, bottom - ROW + 2, Component.translatable("screen.cobblemon_npc.add_node"), width = half) { addNode() }
        button(x + half + 4, bottom - ROW + 2, Component.translatable("screen.cobblemon_npc.remove_node"), UiButtonVariant.DANGER, half) {
            removeNode()
        }.active = dialogue.nodes.size > 1
    }

    private fun box(name: Component, x: Int, y: Int, width: Int, height: Int, value: String): MultiLineEditBox {
        label(name, x, y)
        val box = MultiLineEditBox(font, x, y + LABEL, width, height.coerceAtLeast(FIELD_HEIGHT), Component.empty(), name)
        box.setCharacterLimit(4000)
        box.value = value
        return addRenderableWidget(box)
    }

    /** Writes the fields back into [dialogue]; a renamed node takes its references along. */
    private fun commit() {
        val node = dialogue.nodes[selected] ?: return
        val edited = DialogueNode(
            lines = DialogueEditorText.lines(boxes["lines"]?.value ?: return),
            choices = DialogueEditorText.choices(boxes["choices"]!!.value),
            branches = DialogueEditorText.branches(boxes["branches"]!!.value),
            next = fields["next"]!!.value.trim().ifEmpty { null },
            commands = DialogueEditorText.lines(boxes["commands"]!!.value),
        )
        dialogue = dialogue.copy(
            nodes = LinkedHashMap(dialogue.nodes).also { it[selected] = if (edited == node) node else edited },
            speaker = fields["speaker"]!!.value.trim().ifEmpty { null },
            skin = fields["skin"]!!.value.trim().ifEmpty { null },
        )
        val renamed = fields["node"]!!.value.trim()
        if (renamed == selected) return
        when {
            !DialogueIds.isNodeId(renamed) -> showResult(false, problemText("node_id", listOf(renamed)))
            renamed in dialogue.nodes -> showResult(false, Component.translatable("screen.cobblemon_npc.node_taken", renamed))
            else -> {
                dialogue = DialogueEditorText.rename(dialogue, selected, renamed)
                selected = renamed
            }
        }
    }

    private fun select(nodeId: String) {
        commit()
        selected = nodeId
        rebuildWidgets()
    }

    private fun addNode() {
        commit()
        val nodeId = generateSequence(dialogue.nodes.size + 1) { it + 1 }.map { "node_$it" }.first { it !in dialogue.nodes }
        dialogue = dialogue.copy(nodes = LinkedHashMap(dialogue.nodes).also { it[nodeId] = DialogueNode(lines = listOf("...")) })
        selected = nodeId
        listOffset = dialogue.nodes.size
        rebuildWidgets()
    }

    private fun removeNode() {
        if (dialogue.nodes.size <= 1) return
        commit()
        val nodes = LinkedHashMap(dialogue.nodes).also { it.remove(selected) }
        dialogue = dialogue.copy(nodes = nodes, start = dialogue.start.takeIf { it in nodes } ?: nodes.keys.first())
        selected = dialogue.start
        rebuildWidgets()
    }

    private fun save(preview: Boolean) {
        commit()
        val problem = DialogueCodec.validate(dialogue).firstOrNull()
        if (problem != null) {
            showResult(false, problemText(problem.key, problem.args))
            return
        }
        if (preview) NpcClientState.previewReturn = this
        ClientPlayNetworking.send(DialogueStorePayload(id, DialogueCodec.encode(dialogue), preview))
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (mouseX < MARGIN + 10 + LIST_WIDTH && scrollY != 0.0) {
            commit()
            listOffset -= scrollY.toInt().coerceIn(-1, 1)
            rebuildWidgets()
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun resize(minecraft: Minecraft, width: Int, height: Int) {
        commit()
        super.resize(minecraft, width, height)
    }

    override fun onClose() {
        minecraft?.setScreen(parent)
    }

    private fun problemText(key: String, args: List<String>) =
        Component.translatable("cobblemon_npc.problem.$key", *args.toTypedArray())

    companion object {
        private const val MARGIN = 8
        private const val LIST_WIDTH = 100
        private const val ROW = 22
        private const val LABEL = 11
        private const val GAP = 5
    }
}

package jbro.cobblemon.npc.client

import jbro.cobblemon.npc.dialogue.DialogueCodec
import jbro.cobblemon.npc.dialogue.DialogueEditorText
import jbro.cobblemon.npc.dialogue.DialogueEditorText.BranchRow
import jbro.cobblemon.npc.dialogue.DialogueEditorText.CommandRow
import jbro.cobblemon.npc.dialogue.DialogueEditorText.ConditionKind
import jbro.cobblemon.npc.dialogue.DialogueIds
import jbro.cobblemon.npc.dialogue.DialogueNode
import jbro.cobblemon.npc.dialogue.NpcDialogue
import jbro.cobblemon.npc.network.DialogueStorePayload
import jbro.cobblemon.uikit.UiButtonVariant
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.CommandSuggestions
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.MultiLineEditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * The node editor: the dialogue's nodes on the left, the selected node's parts on the right. Lines and choices are
 * written one per line (`answer -> node ? condition` for a choice). Branches and commands are rows; a command, and a
 * branch whose condition is a command, completes as it is typed, the way a command block does.
 */
class DialogueEditorScreen(private val id: String, json: String, private val parent: Screen?) :
    NpcEditorScreen(Component.translatable("screen.cobblemon_npc.dialogue_editor", id)) {
    private var dialogue: NpcDialogue
    private var selected: String
    private var listOffset = 0
    private var branchOffset = 0
    private var commandOffset = 0
    private val fields = mutableMapOf<String, EditBox>()
    private val boxes = mutableMapOf<String, MultiLineEditBox>()

    // The selected node's rows; they outlive widget rebuilds and are written back by [commit].
    private var branches = mutableListOf<BranchRow>()
    private var commands = mutableListOf<CommandRow>()
    private val suggestions = mutableMapOf<EditBox, CommandSuggestions>()
    private var branchArea = Area(0, 0, 0)
    private var commandArea = Area(0, 0, 0)

    private data class Area(val top: Int, val bottom: Int, val left: Int)

    init {
        val decoded = DialogueCodec.decode(json)
        dialogue = decoded.dialogue?.takeIf { it.nodes.isNotEmpty() }
            ?: NpcDialogue("start", linkedMapOf("start" to DialogueNode(lines = listOf("..."))))
        selected = dialogue.start.takeIf { it in dialogue.nodes } ?: dialogue.nodes.keys.first()
        loadRows()
        decoded.problems.firstOrNull()?.let { showResult(false, problemText(it.key, it.args)) }
    }

    private fun loadRows() {
        val node = dialogue.nodes.getValue(selected)
        branches = node.branches.map(BranchRow::of).toMutableList()
        commands = node.commands.map(CommandRow::of).toMutableList()
        branchOffset = 0
        commandOffset = 0
    }

    override fun init() {
        super.init()
        fields.clear()
        boxes.clear()
        suggestions.clear()
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

        val actionsY = bottom - 30
        nodeList(left + 10, y, actionsY - 6)

        val node = dialogue.nodes.getValue(selected)
        val areaLeft = left + 10 + LIST_WIDTH + 10
        val areaRight = right - 10
        val half = (areaRight - areaLeft - 8) / 2
        val secondLeft = areaLeft + half + 8

        // The node's name and where it goes when no branch holds.
        label(Component.translatable("screen.cobblemon_npc.field.node"), areaLeft, y)
        val startWidth = 70
        fields["node"] = field(areaLeft, y + LABEL, half - startWidth - 4, selected, Component.empty(), 64)
        button(areaLeft + half - startWidth, y + LABEL, Component.translatable(
            if (dialogue.start == selected) "screen.cobblemon_npc.is_start" else "screen.cobblemon_npc.make_start"), width = startWidth) {
            commit()
            dialogue = dialogue.copy(start = selected)
            rebuildWidgets()
        }.active = dialogue.start != selected
        label(Component.translatable("screen.cobblemon_npc.field.next"), secondLeft, y)
        fields["next"] = field(secondLeft, y + LABEL, half, node.next.orEmpty(), Component.translatable("screen.cobblemon_npc.hint.next"), 64)
        y += LABEL + FIELD_HEIGHT + GAP

        // Lines and answers above, branches and commands below, sharing what height there is.
        val rest = actionsY - 6 - y
        val textHeight = (rest - 2 * (LABEL + GAP)) / 2
        boxes["lines"] = box(Component.translatable("screen.cobblemon_npc.field.lines"), areaLeft, y, half, textHeight,
            DialogueEditorText.ofLines(node.lines))
        boxes["choices"] = box(Component.translatable("screen.cobblemon_npc.field.choices"), secondLeft, y, half, textHeight,
            DialogueEditorText.ofChoices(node.choices))
        y += LABEL + textHeight.coerceAtLeast(FIELD_HEIGHT) + GAP

        branchArea = Area(y, actionsY - 6, areaLeft)
        commandArea = Area(y, actionsY - 6, secondLeft)
        branchRows(areaLeft, y, half, actionsY - 6)
        commandRows(secondLeft, y, half, actionsY - 6)

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

    private fun branchRows(x: Int, top: Int, width: Int, bottom: Int) {
        sectionHeader(Component.translatable("screen.cobblemon_npc.field.branches"), x, top, width) {
            branches += BranchRow(ConditionKind.TAG, false, "", "")
            branchOffset = branches.size
        }
        val visible = ((bottom - top - HEADER) / ROW).coerceAtLeast(1)
        branchOffset = branchOffset.coerceIn(0, (branches.size - visible).coerceAtLeast(0))
        branches.withIndex().drop(branchOffset).take(visible).forEachIndexed { slot, (index, row) ->
            val y = top + HEADER + slot * ROW
            val kindWidth = 40
            val notWidth = 16
            val nextWidth = 64
            button(x, y, Component.translatable("screen.cobblemon_npc.kind.${row.kind.name.lowercase()}"), width = kindWidth) {
                row.kind = row.kind.next()
                if (row.kind == ConditionKind.RAW) row.negated = false
                rows()
            }
            val notButton = button(x + kindWidth + 2, y, Component.literal("!"),
                if (row.negated) UiButtonVariant.DANGER else UiButtonVariant.GHOST, notWidth) {
                row.negated = !row.negated
                rows()
            }
            notButton.active = row.kind != ConditionKind.RAW
            val valueLeft = x + kindWidth + notWidth + 4
            val valueWidth = width - (valueLeft - x) - nextWidth - REMOVE - 16
            val value = field(valueLeft, y + 1, valueWidth, row.value,
                Component.translatable("screen.cobblemon_npc.hint.kind.${row.kind.name.lowercase()}"), 512)
            value.setResponder { row.value = it; suggestions[value]?.updateCommandInfo() }
            if (row.kind == ConditionKind.COMMAND) suggest(value)
            label(Component.literal("→"), valueLeft + valueWidth + 3, y + 6)
            val next = field(valueLeft + valueWidth + 12, y + 1, nextWidth, row.next, Component.translatable("screen.cobblemon_npc.hint.node"), 64)
            next.setResponder { row.next = it }
            removeButton(x + width - REMOVE, y) { branches.removeAt(index) }
        }
    }

    private fun commandRows(x: Int, top: Int, width: Int, bottom: Int) {
        sectionHeader(Component.translatable("screen.cobblemon_npc.field.commands"), x, top, width) {
            commands += CommandRow(false, "")
            commandOffset = commands.size
        }
        val visible = ((bottom - top - HEADER) / ROW).coerceAtLeast(1)
        commandOffset = commandOffset.coerceIn(0, (commands.size - visible).coerceAtLeast(0))
        commands.withIndex().drop(commandOffset).take(visible).forEachIndexed { slot, (index, row) ->
            val y = top + HEADER + slot * ROW
            val whoWidth = 40
            button(x, y, Component.translatable(if (row.asServer) "screen.cobblemon_npc.as_server" else "screen.cobblemon_npc.as_player"),
                if (row.asServer) UiButtonVariant.DANGER else UiButtonVariant.SECONDARY, whoWidth) {
                row.asServer = !row.asServer
                rows()
            }
            val value = field(x + whoWidth + 2, y + 1, width - whoWidth - REMOVE - 4, row.command,
                Component.translatable("screen.cobblemon_npc.hint.command"), 512)
            value.setResponder { row.command = it; suggestions[value]?.updateCommandInfo() }
            suggest(value)
            removeButton(x + width - REMOVE, y) { commands.removeAt(index) }
        }
    }

    private fun sectionHeader(name: Component, x: Int, top: Int, width: Int, add: () -> Unit) {
        label(name, x, top + 6)
        button(x + width - 40, top, Component.translatable("screen.cobblemon_npc.add_row"), width = 40) {
            add()
            rows()
        }
    }

    private fun removeButton(x: Int, y: Int, remove: () -> Unit) {
        button(x, y, Component.literal("×"), UiButtonVariant.GHOST, REMOVE) {
            remove()
            rows()
        }
    }

    /** Completes [box] as a command, against the commands the server sent this player. */
    private fun suggest(box: EditBox) {
        val completion = CommandSuggestions(minecraft!!, this, box, font, true, true, 0, 7, false, Int.MIN_VALUE)
        completion.setAllowSuggestions(true)
        completion.updateCommandInfo()
        suggestions[box] = completion
    }

    private fun activeSuggestions(): CommandSuggestions? = (focused as? EditBox)?.let(suggestions::get)

    /** Rebuilds after a row changed, keeping what the text boxes hold. */
    private fun rows() {
        commit()
        rebuildWidgets()
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        activeSuggestions()?.render(graphics, mouseX, mouseY)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (activeSuggestions()?.keyPressed(keyCode, scanCode, modifiers) == true) return true
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (activeSuggestions()?.mouseClicked(mouseX, mouseY, button) == true) return true
        return super.mouseClicked(mouseX, mouseY, button)
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

    /** Writes the fields and rows back into [dialogue]; a renamed node takes its references along. Empty rows stay in the editor only. */
    private fun commit() {
        val node = dialogue.nodes[selected] ?: return
        val edited = DialogueNode(
            lines = DialogueEditorText.lines(boxes["lines"]?.value ?: return),
            choices = DialogueEditorText.choices(boxes["choices"]!!.value),
            branches = branches.filter { it.value.isNotBlank() || it.next.isNotBlank() }.map(BranchRow::toBranch),
            next = fields["next"]!!.value.trim().ifEmpty { null },
            commands = commands.filterNot(CommandRow::blank).map(CommandRow::write),
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
                branches.forEach { if (it.next.trim() == selected) it.next = renamed }
                selected = renamed
            }
        }
    }

    private fun select(nodeId: String) {
        commit()
        selected = nodeId
        loadRows()
        rebuildWidgets()
    }

    private fun addNode() {
        commit()
        val nodeId = generateSequence(dialogue.nodes.size + 1) { it + 1 }.map { "node_$it" }.first { it !in dialogue.nodes }
        dialogue = dialogue.copy(nodes = LinkedHashMap(dialogue.nodes).also { it[nodeId] = DialogueNode(lines = listOf("...")) })
        selected = nodeId
        listOffset = dialogue.nodes.size
        loadRows()
        rebuildWidgets()
    }

    private fun removeNode() {
        if (dialogue.nodes.size <= 1) return
        commit()
        val nodes = LinkedHashMap(dialogue.nodes).also { it.remove(selected) }
        dialogue = dialogue.copy(nodes = nodes, start = dialogue.start.takeIf { it in nodes } ?: nodes.keys.first())
        selected = dialogue.start
        loadRows()
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
        if (activeSuggestions()?.mouseScrolled(scrollY) == true) return true
        if (scrollY == 0.0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        val step = scrollY.toInt().coerceIn(-1, 1)
        when {
            mouseX < MARGIN + 10 + LIST_WIDTH -> listOffset -= step
            mouseY >= branchArea.top && mouseY < branchArea.bottom && mouseX < commandArea.left -> branchOffset -= step
            mouseY >= commandArea.top && mouseY < commandArea.bottom && mouseX >= commandArea.left -> commandOffset -= step
            else -> return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        }
        rows()
        return true
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
        private const val HEADER = 22
        private const val REMOVE = 16
    }
}

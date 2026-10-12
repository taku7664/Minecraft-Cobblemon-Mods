package jbro.cobblemon.npc.client

import jbro.cobblemon.npc.dialogue.*
import jbro.cobblemon.npc.dialogue.DialogueEditorText.BranchRow
import jbro.cobblemon.npc.dialogue.DialogueEditorText.CommandRow
import jbro.cobblemon.npc.dialogue.DialogueEditorText.ConditionKind
import jbro.cobblemon.npc.network.DialogueStorePayload
import jbro.cobblemon.uikit.UiButtonVariant
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CommandSuggestions
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.MultiLineEditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/** Page/choice forms are the default; conditions, commands and IDs have separate advanced tabs. */
class DialogueEditorScreen(private val id: String, json: String, private val parent: Screen?) :
    NpcEditorScreen(Component.translatable("screen.cobblemon_npc.dialogue_editor", id)) {
    private enum class Tab { PAGES, CHOICES, BRANCHES, COMMANDS, DETAILS }
    private val decoded = DialogueCodec.decode(json)
    private val draft = DialogueDraft(decoded.dialogue?.takeIf { it.nodes.isNotEmpty() }
        ?: NpcDialogue("start", linkedMapOf("start" to DialogueNode(lines = listOf("...")))))
    private var selected = draft.value.start.takeIf { it in draft.value.nodes } ?: draft.value.nodes.keys.first()
    private var tab = Tab.PAGES
    private var advanced = false
    private var listOffset = 0
    private var item = 0
    private var query = ""
    private var listRefresh = false
    private val listWidgets = mutableListOf<Button>()
    private var selectedWidget: Button? = null
    private val suggestions = mutableMapOf<EditBox, CommandSuggestions>()
    private var pending: NpcDialogue? = null
    private var closeAfterSave = false
    private var acknowledgedSave = false
    private var contentX = 0
    private var contentW = 0
    private var contentBottom = 0
    private var undoButton: Button? = null
    private var redoButton: Button? = null
    private var saveButton: Button? = null

    init { decoded.problems.firstOrNull()?.let { showResult(false, problemText(it)) } }

    override fun init() {
        super.init()
        suggestions.clear(); listWidgets.clear()
        selected = selected.takeIf { it in draft.value.nodes } ?: draft.value.start.takeIf { it in draft.value.nodes } ?: draft.value.nodes.keys.first()
        panel(8, 8, width - 16, height - 16, title)
        if (width < 320 || height < 240) {
            label(tr("small_screen"), 20, 48)
            button(20, height - 44, tr("close")) { onClose() }
            return
        }
        val sideW = (width / 4).coerceIn(100, 180)
        contentX = 20 + sideW + 10
        contentW = width - contentX - 20
        contentBottom = height - 50
        undoButton = button(20, 30, tr("undo"), width = 48) { if (draft.undo()) resetView() }
        redoButton = button(72, 30, tr("redo"), width = 48) { if (draft.redo()) resetView() }
        button(width - 100, 30, tr(if (advanced) "basic" else "advanced"), width = 80) {
            advanced = !advanced
            if (!advanced && tab !in listOf(Tab.PAGES, Tab.CHOICES)) tab = Tab.PAGES
            rebuild()
        }
        field(20, 54, sideW, query, tr("hint.search"), 64).setResponder { query = it; listOffset = 0; listRefresh = true }
        refreshNodes(sideW)
        val half = (sideW - 4) / 2
        button(20, height - 76, tr("add_node"), width = half) {
            val next = generateSequence(1) { it + 1 }.map { "node_$it" }.first { it !in draft.value.nodes }
            draft.replace(draft.value.copy(nodes = LinkedHashMap(draft.value.nodes).also { it[next] = DialogueNode(lines = listOf("")) }))
            selected = next; query = ""; item = 0; tab = Tab.PAGES; listOffset = draft.value.nodes.size
            rebuild()
        }
        button(24 + half, height - 76, tr("remove_node"), UiButtonVariant.DANGER, half) { removeNode() }.active = draft.value.nodes.size > 1
        val tabs = if (advanced) Tab.entries else listOf(Tab.PAGES, Tab.CHOICES)
        val each = (contentW - (tabs.size - 1) * 3) / tabs.size
        tabs.forEachIndexed { index, value ->
            button(contentX + index * (each + 3), 54, tr("tab.${value.name.lowercase()}"), width = each) {
                tab = value; item = 0; rebuild()
            }.active = value != tab
        }
        when (tab) {
            Tab.PAGES -> pages()
            Tab.CHOICES -> choices()
            Tab.BRANCHES -> branches()
            Tab.COMMANDS -> commands()
            Tab.DETAILS -> details()
        }
        val actionsX = width - 238
        saveButton = button(actionsX, height - 42, tr("save"), UiButtonVariant.PRIMARY, 70) { save() }
        button(actionsX + 74, height - 42, tr("draft_preview"), width = 80) {
            draft.checkpoint()
            minecraft!!.setScreen(DialogueDraftPreviewScreen(this, draft.value, selected))
        }.setTooltip(Tooltip.create(tr("preview_hint")))
        button(actionsX + 158, height - 42, tr("close"), width = 60) { onClose() }
        if (pending != null) children().filterIsInstance<net.minecraft.client.gui.components.AbstractWidget>().forEach { it.active = false }
    }

    private fun node() = draft.value.nodes.getValue(selected)
    private fun edit(group: String? = null, change: (DialogueNode) -> DialogueNode) = draft.editNode(selected, group, change)
    private fun tr(key: String, vararg args: Any) = Component.translatable("screen.cobblemon_npc.$key", *args)
    private fun rebuild() { draft.checkpoint(); rebuildWidgets() }
    private fun resetView() { item = 0; status = null; rebuild() }

    private fun refreshNodes(sideW: Int = contentX - 30) {
        listWidgets.forEach(::removeWidget); listWidgets.clear()
        selectedWidget = null
        val ids = draft.value.nodes.filter { (key, value) ->
            query.isBlank() || key.contains(query, true) || value.lines.any { it.contains(query, true) }
        }.keys.toList()
        val visible = ((height - 180) / 22).coerceAtLeast(1)
        listOffset = listOffset.coerceIn(0, (ids.size - visible).coerceAtLeast(0))
        ids.drop(listOffset).take(visible).forEachIndexed { index, key ->
            val first = draft.value.nodes.getValue(key).lines.firstOrNull().orEmpty().replace('\n', ' ')
            val caption = Component.literal("${if (key == draft.value.start) "★ " else ""}$key — $first")
            listWidgets += button(20, 78 + index * 22, caption, width = sideW) {
                selected = key; item = 0; status = null; rebuild()
            }.also { if (key == selected) selectedWidget = it; it.setTooltip(Tooltip.create(caption)) }
        }
        listWidgets += button(20, height - 100, Component.literal("◀"), width = 28) { listOffset -= visible; refreshNodes(sideW) }.also { it.active = listOffset > 0 }
        listWidgets += button(52, height - 100, Component.literal("▶"), width = 28) { listOffset += visible; refreshNodes(sideW) }.also { it.active = listOffset + visible < ids.size }
    }

    /** One item at a time keeps long dialogues from shrinking fields or hiding offscreen rows. */
    private fun itemHeader(count: Int, name: String, add: () -> Unit, remove: () -> Unit, maximum: Int = Int.MAX_VALUE) {
        item = item.coerceIn(0, (count - 1).coerceAtLeast(0))
        label(tr(name, if (count == 0) 0 else item + 1, count), contentX, 87, (contentW - 112).coerceAtLeast(1))
        button(contentX + contentW - 108, 80, Component.literal("◀"), width = 24) { item--; rebuild() }.active = item > 0
        button(contentX + contentW - 82, 80, Component.literal("▶"), width = 24) { item++; rebuild() }.active = item + 1 < count
        button(contentX + contentW - 56, 80, Component.literal("+"), width = 24) { add(); rebuild() }.also {
            it.active = count < maximum
            it.setTooltip(Tooltip.create(if (count < maximum) tr("add_row") else tr("item_limit", maximum)))
        }
        button(contentX + contentW - 30, 80, Component.literal("×"), UiButtonVariant.DANGER, 30) { remove(); rebuild() }
            .also { it.active = count > 0; it.setTooltip(Tooltip.create(tr("remove_item"))) }
    }

    private fun pages() {
        itemHeader(node().lines.size, "page_count", {
            edit { it.copy(lines = it.lines + "") }; item = node().lines.lastIndex
        }, { edit { it.copy(lines = it.lines.filterIndexed { index, _ -> index != item }) } }, DialogueNode.MAX_ITEMS)
        if (node().lines.isNotEmpty()) {
            val page = item
            val box = MultiLineEditBox(font, contentX, 106, contentW, (contentBottom - 134).coerceAtLeast(20), Component.empty(), tr("tab.pages"))
            box.setCharacterLimit(maxOf(4000, node().lines[page].length))
            box.value = node().lines[page]
            box.setValueListener { value -> edit("page:$selected:$page") { it.copy(lines = it.lines.toMutableList().also { pages -> pages[page] = value }) } }
            box.setTooltip(Tooltip.create(tr("page_hint")))
            addRenderableWidget(box)
        } else label(tr("pages_empty"), contentX, 112, contentW)
        target(contentX, contentBottom - 24, contentW, node().next, true) { next -> edit { it.copy(next = next) } }
    }

    private fun choices() {
        itemHeader(node().choices.size, "choice_count", {
            edit { it.copy(choices = it.choices + DialogueChoice("", null)) }; item = node().choices.lastIndex
        }, { edit { it.copy(choices = it.choices.filterIndexed { index, _ -> index != item }) } }, DialogueNode.MAX_ITEMS)
        if (node().choices.isEmpty()) { label(tr("choices_empty"), contentX, 112, contentW); return }
        val index = item
        fun change(group: String? = null, apply: (DialogueChoice) -> DialogueChoice) {
            edit(group) { it.copy(choices = it.choices.toMutableList().also { list -> list[index] = apply(list[index]) }) }
        }
        label(tr("choice_text"), contentX, 104, contentW)
        field(contentX, 115, contentW, node().choices[index].text, tr("choice_text_hint"), maxOf(4000, node().choices[index].text.length))
            .setResponder { text -> change("choice:$selected:$index") { it.copy(text = text) } }
        target(contentX, 140, contentW, node().choices[index].next, true) { next -> change { it.copy(next = next) } }
        condition(contentX, 165, contentW, node().choices[index].condition) { text -> change("choice-condition:$selected:$index") { it.copy(condition = text) } }
    }

    private fun branches() {
        itemHeader(node().branches.size, "branch_count", {
            edit { it.copy(branches = it.branches + DialogueBranch("tag:", selected)) }; item = node().branches.lastIndex
        }, { edit { it.copy(branches = it.branches.filterIndexed { index, _ -> index != item }) } })
        if (node().branches.isEmpty()) { label(tr("branches_empty"), contentX, 112, contentW); return }
        val index = item
        fun change(group: String? = null, apply: (DialogueBranch) -> DialogueBranch) {
            edit(group) { it.copy(branches = it.branches.toMutableList().also { list -> list[index] = apply(list[index]) }) }
        }
        condition(contentX, 106, contentW, node().branches[index].condition, false) { text -> change("branch:$selected:$index") { it.copy(condition = text.orEmpty()) } }
        target(contentX, 132, contentW, node().branches[index].next, false) { next -> change { it.copy(next = next!!) } }
        label(tr("branch_hint"), contentX, 160, contentW)
    }

    private fun commands() {
        itemHeader(node().commands.size, "command_count", {
            edit { it.copy(commands = it.commands + "/") }; item = node().commands.lastIndex
        }, { edit { it.copy(commands = it.commands.filterIndexed { index, _ -> index != item }) } })
        if (node().commands.isEmpty()) { label(tr("commands_empty"), contentX, 112, contentW); return }
        val index = item
        val row = CommandRow.of(node().commands[index])
        fun change() { edit("command:$selected:$index") { it.copy(commands = it.commands.toMutableList().also { list -> list[index] = row.write() }) } }
        button(contentX, 110, tr(if (row.asServer) "as_server" else "as_player"), if (row.asServer) UiButtonVariant.DANGER else UiButtonVariant.SECONDARY, 52) {
            row.asServer = !row.asServer; change(); rebuild()
        }.setTooltip(Tooltip.create(tr("command_rights_hint")))
        val command = field(contentX + 56, 110, contentW - 56, row.command, tr("hint.command"), maxOf(4000, row.command.length))
        command.setResponder { row.command = it; change(); suggestions[command]?.updateCommandInfo() }
        suggest(command)
        label(tr("command_hint"), contentX, 140, contentW)
    }

    private fun details() {
        label(tr("field.node"), contentX, 80, contentW)
        var newId = selected
        field(contentX, 91, contentW - 56, selected, tr("hint.node"), 64).setResponder { newId = it.trim() }
        button(contentX + contentW - 52, 91, tr("rename"), width = 52) {
            if (draft.renameNode(selected, newId)) { selected = newId; rebuild() }
            else showResult(false, tr("rename_failed"))
        }
        button(contentX, 116, tr(if (draft.value.start == selected) "is_start" else "make_start"), width = 72) {
            draft.replace(draft.value.copy(start = selected)); rebuild()
        }.active = draft.value.start != selected
        field(contentX + 78, 116, contentW - 78, draft.value.speaker.orEmpty(), tr("field.speaker"), maxOf(64, draft.value.speaker.orEmpty().length))
            .setResponder { draft.replace(draft.value.copy(speaker = it.ifBlank { null }), "speaker") }
        val pickerW = 52
        field(contentX, 142, contentW - pickerW - 4, draft.value.skin.orEmpty(), tr("hint.skin"), maxOf(128, draft.value.skin.orEmpty().length))
            .setResponder { draft.replace(draft.value.copy(skin = it.ifBlank { null }), "skin") }
        button(contentX + contentW - pickerW, 142, tr("pick_skin"), width = pickerW) {
            draft.checkpoint()
            minecraft!!.setScreen(NpcSkinPickerScreen(this, draft.value.skin.orEmpty()) { skin -> draft.replace(draft.value.copy(skin = skin)) })
        }
        label(tr("details_hint"), contentX, 169, contentW)
    }

    private fun target(x: Int, y: Int, w: Int, next: String?, allowEnd: Boolean, pick: (String?) -> Unit) {
        val text = if (next == null) tr("end") else Component.literal("→ $next — ${draft.value.nodes[next]?.lines?.firstOrNull().orEmpty().replace('\n', ' ')}")
        button(x, y, text, width = w) {
            draft.checkpoint()
            minecraft!!.setScreen(NpcNodePickerScreen(this, draft.value, allowEnd, pick))
        }.setTooltip(Tooltip.create(Component.translatable("screen.cobblemon_npc.target_hint", text)))
    }

    /** Simple conditions use forms; compound expressions remain intact in the raw form. */
    private fun condition(x: Int, y: Int, w: Int, text: String?, allowAlways: Boolean = true, change: (String?) -> Unit) {
        val row = text?.let { BranchRow.of(DialogueBranch(it, "")) }
        val kinds = (if (allowAlways) listOf<ConditionKind?>(null) else emptyList()) + ConditionKind.entries
        button(x, y, if (row == null) tr("always") else tr("kind.${row.kind.name.lowercase()}"), width = 46) {
            val next = kinds[(kinds.indexOf(row?.kind) + 1) % kinds.size]
            change(next?.write(false, row?.value.orEmpty())); rebuild()
        }.setTooltip(Tooltip.create(tr("condition_hint")))
        if (row == null) return
        button(x + 49, y, Component.literal(if (row.negated) "!" else "="), width = 24) {
            row.negated = !row.negated; change(row.toBranch().condition); rebuild()
        }.also { it.active = row.kind != ConditionKind.RAW; it.setTooltip(Tooltip.create(tr("negate_hint"))) }
        val value = field(x + 76, y, w - 76, row.value, tr("hint.kind.${row.kind.name.lowercase()}"), maxOf(4000, row.value.length))
        value.setResponder { row.value = it; change(row.toBranch().condition); suggestions[value]?.updateCommandInfo() }
        if (row.kind == ConditionKind.COMMAND) suggest(value)
    }

    private fun suggest(box: EditBox) {
        if (minecraft?.connection == null) return
        suggestions[box] = CommandSuggestions(minecraft!!, this, box, font, true, true, 0, 7, false, Int.MIN_VALUE).also {
            it.setAllowSuggestions(true); it.updateCommandInfo()
        }
    }
    private fun activeSuggestions() = (focused as? EditBox)?.let(suggestions::get)

    private fun removeNode() {
        val removed = selected
        minecraft!!.setScreen(ConfirmScreen({ yes ->
            if (yes) { draft.removeNode(removed); selected = draft.value.start; item = 0 }
            minecraft!!.setScreen(this)
        }, tr("remove_node"), tr("remove_node_hint", removed, draft.referencesTo(removed))))
    }

    private fun save(close: Boolean = false) {
        if (pending != null) return
        if (minecraft?.connection == null || !ClientPlayNetworking.canSend(DialogueStorePayload.TYPE)) {
            showResult(false, tr("connection_lost")); return
        }
        val issue = DialogueCodec.validateLocated(draft.value).firstOrNull()
        if (issue != null) { closeAfterSave = false; focusIssue(issue); showResult(false, problemText(issue.problem)); return }
        val snapshot = draft.value
        val json = DialogueCodec.encode(snapshot)
        if (json.length > 262144) { showResult(false, tr("too_large")); return }
        pending = snapshot; closeAfterSave = close
        showResult(true, tr("saving"))
        children().filterIsInstance<net.minecraft.client.gui.components.AbstractWidget>().forEach { it.active = false }
        ClientPlayNetworking.send(DialogueStorePayload(id, json, false))
    }

    override fun onServerResult(ok: Boolean, key: String, args: List<String>) {
        super.onServerResult(ok, key, args)
        val submitted = pending ?: return
        if (ok && key == "message.cobblemon_npc.dialogue_saved" && args.firstOrNull() == id) {
            acknowledgedSave = true
            draft.markSaved(submitted); pending = null
            if (closeAfterSave && !draft.dirty) { minecraft?.setScreen(parent); return }
        } else if (!ok) pending = null
        closeAfterSave = false
        if (pending == null) rebuildWidgets()
    }

    override fun onClose() {
        if (pending != null) { showResult(false, tr("wait_save")); return }
        if (draft.dirty) minecraft?.setScreen(NpcUnsavedScreen(this, { save(true) }, { minecraft?.setScreen(parent) }))
        else minecraft?.setScreen(parent)
    }

    override fun tick() {
        super.tick()
        undoButton?.active = draft.canUndo && pending == null
        redoButton?.active = draft.canRedo && pending == null
        saveButton?.active = pending == null
        if (listRefresh) { listRefresh = false; refreshNodes() }
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        if (width >= 320 && height >= 240) {
            val issue = currentIssue()
            val problem = issue?.problem
            val text = problem?.let(::problemText) ?: tr(if (pending != null) "saving" else if (draft.dirty) "unsaved" else if (acknowledgedSave) "saved" else "unchanged")
            graphics.drawString(font, font.plainSubstrByWidth(text.string, (width - 236).coerceAtLeast(1)), 126, 36, if (problem != null) 0xFF7777 else 0xC0C0C0)
            graphics.renderOutline(contentX - 4, 78, contentW + 8, contentBottom - 74, 0xFF454953.toInt())
            if (problem != null && mouseX in 126..(width - 110) && mouseY in 30..50) {
                setTooltipForNextRenderPass(problemText(problem).copy().append("\n").append(tr("problem_location")))
            }
            // Current selection uses a border, distinct from the start star and the Save text colour.
            selectedWidget?.let {
                graphics.renderOutline(it.x - 1, it.y - 1, it.width + 2, it.height + 2, 0xFF86B8E8.toInt())
            }
            if (issue?.node == selected && (issue.index < 0 || issue.index == item)) {
                val errorY = when {
                    issue.part == DialogueIssue.Part.CHOICE && tab == Tab.CHOICES -> when (problem?.key) {
                        "choice_text" -> 115
                        "next" -> 140
                        else -> 165
                    }
                    issue.part == DialogueIssue.Part.BRANCH && tab == Tab.BRANCHES -> if (problem?.key == "next") 132 else 106
                    issue.part == DialogueIssue.Part.COMMAND && tab == Tab.COMMANDS -> 110
                    issue.part == DialogueIssue.Part.NEXT && tab == Tab.PAGES -> contentBottom - 24
                    issue.part == DialogueIssue.Part.NODE && tab == Tab.DETAILS -> 91
                    else -> null
                }
                errorY?.let { graphics.renderOutline(contentX - 1, it - 1, contentW + 2, 22, 0xFFFF7777.toInt()) }
            }
        }
        activeSuggestions()?.render(graphics, mouseX, mouseY)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (hasControlDown() && keyCode in listOf(GLFW.GLFW_KEY_Z, GLFW.GLFW_KEY_Y, GLFW.GLFW_KEY_S)) {
            if (keyCode == GLFW.GLFW_KEY_S) save()
            else if (pending == null && (if (keyCode == GLFW.GLFW_KEY_Y || hasShiftDown()) draft.redo() else draft.undo())) resetView()
            return true
        }
        if (activeSuggestions()?.keyPressed(keyCode, scanCode, modifiers) == true) return true
        return super.keyPressed(keyCode, scanCode, modifiers)
    }
    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (pending == null && button == 0 && mouseX in 126.0..(width - 110.0) && mouseY in 30.0..50.0) {
            currentIssue()?.let { focusIssue(it); return true }
        }
        if (activeSuggestions()?.mouseClicked(mouseX, mouseY, button) == true) return true
        draft.checkpoint()
        return super.mouseClicked(mouseX, mouseY, button)
    }
    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (activeSuggestions()?.mouseScrolled(scrollY) == true) return true
        if (mouseX < contentX && mouseY in 78.0..(height - 104.0) && scrollY != 0.0) {
            listOffset -= if (scrollY > 0) 3 else -3; refreshNodes(); return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }
    private fun problemText(problem: DialogueProblem) = Component.translatable("cobblemon_npc.problem.${problem.key}", *problem.args.toTypedArray())

    private fun currentIssue(): DialogueIssue? {
        val issues = DialogueCodec.validateLocated(draft.value)
        return issues.firstOrNull { it.node == selected } ?: issues.firstOrNull()
    }

    private fun focusIssue(issue: DialogueIssue) {
        issue.node?.takeIf { it in draft.value.nodes }?.let { selected = it }
        tab = when (issue.part) {
            DialogueIssue.Part.NODE, DialogueIssue.Part.START -> Tab.DETAILS
            DialogueIssue.Part.NEXT -> Tab.PAGES
            DialogueIssue.Part.CHOICE -> Tab.CHOICES
            DialogueIssue.Part.BRANCH -> Tab.BRANCHES
            DialogueIssue.Part.COMMAND -> Tab.COMMANDS
        }
        advanced = tab !in listOf(Tab.PAGES, Tab.CHOICES) || advanced
        item = issue.index.coerceAtLeast(0)
        query = ""
        rebuild()
    }
}

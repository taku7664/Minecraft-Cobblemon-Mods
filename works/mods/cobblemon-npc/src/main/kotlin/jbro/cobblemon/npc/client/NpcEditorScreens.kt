package jbro.cobblemon.npc.client

import jbro.cobblemon.npc.network.DialogueFetchPayload
import jbro.cobblemon.npc.network.NpcSavePayload
import jbro.cobblemon.npc.network.NpcSettingsPayload
import jbro.cobblemon.uikit.UiButtonVariant
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.network.chat.Component

/**
 * What the operator screens share, drawn with Minecraft's own widgets: titles, field labels and a status line. A
 * [UiButtonVariant] only tints the label: yellow marks the chosen row or main action, red a destructive one.
 */
abstract class NpcEditorScreen(title: Component) : Screen(title) {
    private data class Label(val text: Component, val x: Int, val y: Int, val width: Int)
    private val labels = mutableListOf<Label>()
    private val titles = mutableListOf<Triple<Component, Int, Int>>()
    private val panels = mutableListOf<IntArray>()
    protected var status: Component? = null
    protected var statusOk = true

    override fun init() {
        labels.clear()
        titles.clear()
        panels.clear()
    }

    override fun isPauseScreen() = false

    open fun showResult(ok: Boolean, message: Component) {
        status = message
        statusOk = ok
    }

    open fun onServerResult(ok: Boolean, key: String, args: List<String>) {
        showResult(ok, Component.translatable(key, *args.toTypedArray()))
    }

    protected fun label(text: Component, x: Int, y: Int, maxWidth: Int = width - x - 12) {
        labels += Label(text, x, y, maxWidth.coerceAtLeast(1))
    }

    protected fun field(x: Int, y: Int, width: Int, value: String, hint: Component, maxLength: Int = 256): EditBox {
        val box = EditBox(font, x, y, width, FIELD_HEIGHT, hint)
        box.setMaxLength(maxLength)
        box.value = value
        box.setHint(Component.literal(font.plainSubstrByWidth(hint.string, (width - 8).coerceAtLeast(1))))
        if (hint.string.isNotEmpty()) box.setTooltip(Tooltip.create(hint))
        return addRenderableWidget(box)
    }

    protected fun button(x: Int, y: Int, text: Component, variant: UiButtonVariant = UiButtonVariant.SECONDARY,
                         width: Int? = null, press: () -> Unit): Button {
        val buttonWidth = width ?: (font.width(text) + 16).coerceAtLeast(40)
        val clipped = Component.literal(font.plainSubstrByWidth(text.string, (buttonWidth - 8).coerceAtLeast(1)))
        val shown = when (variant) {
            UiButtonVariant.PRIMARY -> clipped.withStyle(ChatFormatting.YELLOW)
            UiButtonVariant.DANGER -> clipped.withStyle(ChatFormatting.RED)
            else -> clipped
        }
        return addRenderableWidget(Button.builder(shown) { press() }.bounds(x, y, buttonWidth, BUTTON_HEIGHT).build()).also {
            if (shown.string != text.string) it.setTooltip(Tooltip.create(text))
        }
    }

    /** Vanilla widgets on a quiet, opaque working surface. */
    protected fun panel(x: Int, y: Int, width: Int, height: Int, title: Component) {
        panels += intArrayOf(x, y, width, height)
        titles += Triple(title, x + width / 2, y + 8)
    }

    override fun renderBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Draw our background directly: inventory-movement mods can cancel Screen.renderBackground.
        renderBlurredBackground(partialTick)
        graphics.fill(0, 0, width, height, 0xA0101010.toInt())
        panels.forEach { (x, y, w, h) ->
            graphics.fill(x, y, x + w, y + h, 0xFF181A20.toInt())
            graphics.renderOutline(x, y, w, h, 0xFF666A73.toInt())
        }
        titles.forEach { (text, x, y) ->
            graphics.drawCenteredString(font, font.plainSubstrByWidth(text.string, (width - 32).coerceAtLeast(1)), x, y, 0xFFFFFF)
        }
        labels.forEach { (text, x, y, w) -> graphics.drawString(font, font.plainSubstrByWidth(text.string, w), x, y, LABEL_COLOR) }
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        status?.let {
            graphics.fill(8, height - 19, width - 8, height - 5, 0xFF181A20.toInt())
            graphics.drawCenteredString(font, font.plainSubstrByWidth(it.string, (width - 32).coerceAtLeast(1)), width / 2, height - 14, if (statusOk) 0x55FF55 else 0xFF5555)
            if (mouseY >= height - 19) setTooltipForNextRenderPass(it)
        }
    }

    companion object {
        const val FIELD_HEIGHT = 20
        const val BUTTON_HEIGHT = 20

        /** The grey vanilla uses for field labels, as on the create-world screen. */
        private const val LABEL_COLOR = 0xA0A0A0
    }
}

/** An NPC's name, skin and dialogue, opened by right-clicking it with the wand. */
class NpcSettingsScreen(private val settings: NpcSettingsPayload) :
    NpcEditorScreen(Component.translatable("screen.cobblemon_npc.settings")) {
    private var name = settings.name
    private var skin = settings.skin
    private var dialogue = settings.dialogue
    private var saved = Triple(name, skin, dialogue)
    private var pending: Triple<String, String, String>? = null
    private var closeAfterSave = false
    private lateinit var nameField: EditBox
    private lateinit var skinField: EditBox
    private lateinit var dialogueField: EditBox

    override fun init() {
        super.init()
        val panelWidth = 320.coerceAtMost(width - 16)
        val panelHeight = 190
        val left = (width - panelWidth) / 2
        val top = ((height - panelHeight) / 2).coerceAtLeast(4)
        panel(left, top, panelWidth, panelHeight, title)
        val labelTexts = listOf("name", "skin", "dialogue").map { Component.translatable("screen.cobblemon_npc.field.$it") }
        // One column of fields after the widest label.
        val fieldLeft = left + 14 + labelTexts.maxOf(font::width) + 10
        val fieldWidth = left + panelWidth - 14 - fieldLeft
        var y = top + 30
        label(labelTexts[0], left + 14, y + 5)
        nameField = field(fieldLeft, y, fieldWidth, name, Component.translatable("screen.cobblemon_npc.hint.name"), 64)
        nameField.setResponder { name = it }
        y += 26
        label(labelTexts[1], left + 14, y + 5)
        val pickWidth = 60
        skinField = field(fieldLeft, y, fieldWidth - pickWidth - 4, skin, Component.translatable("screen.cobblemon_npc.hint.skin"), 128)
        skinField.setResponder { skin = it }
        button(fieldLeft + fieldWidth - pickWidth, y, Component.translatable("screen.cobblemon_npc.pick_skin"), width = pickWidth) {
            remember()
            minecraft!!.setScreen(NpcSkinPickerScreen(this, skin) { picked ->
                // Coming back rebuilds the fields from what they hold, so the field takes the pick too.
                skin = picked
                skinField.value = picked
            })
        }
        y += 26
        label(labelTexts[2], left + 14, y + 5)
        dialogueField = field(fieldLeft, y, fieldWidth - pickWidth - 4, dialogue, Component.translatable("screen.cobblemon_npc.hint.dialogue"), 64)
        dialogueField.setResponder { dialogue = it }
        button(fieldLeft + fieldWidth - pickWidth, y, Component.translatable("screen.cobblemon_npc.pick_dialogue"), width = pickWidth) {
            remember()
            minecraft!!.setScreen(DialoguePickerScreen(this, settings.dialogues, dialogue) { picked ->
                dialogue = picked
                dialogueField.value = picked
            })
        }.active = settings.dialogues.isNotEmpty()
        y += 34

        val buttons = listOf(
            Triple(Component.translatable("screen.cobblemon_npc.save"), UiButtonVariant.PRIMARY) { save() },
            Triple(Component.translatable("screen.cobblemon_npc.edit_dialogue"), UiButtonVariant.SECONDARY) { editDialogue() },
            Triple(Component.translatable("screen.cobblemon_npc.close"), UiButtonVariant.GHOST) { onClose() },
            Triple(Component.translatable("screen.cobblemon_npc.remove"), UiButtonVariant.DANGER) { remove() },
        )
        val buttonWidth = (panelWidth - 28 - 6) / 2
        buttons.forEachIndexed { index, (text, variant, press) ->
            button(left + 14 + (index % 2) * (buttonWidth + 6), y + (index / 2) * 24, text, variant, buttonWidth, press)
        }
        nameField.let(::setInitialFocus)
        if (pending != null) children().filterIsInstance<net.minecraft.client.gui.components.AbstractWidget>().forEach { it.active = false }
    }

    override fun rebuildWidgets() {
        remember()
        super.rebuildWidgets()
    }

    override fun resize(minecraft: net.minecraft.client.Minecraft, width: Int, height: Int) {
        remember()
        super.resize(minecraft, width, height)
    }

    private fun remember() {
        if (!::nameField.isInitialized) return
        name = nameField.value
        skin = skinField.value
        dialogue = dialogueField.value
    }

    private fun save(close: Boolean = false) {
        if (pending != null) return
        if (minecraft?.connection == null || !ClientPlayNetworking.canSend(NpcSavePayload.TYPE)) {
            showResult(false, Component.translatable("screen.cobblemon_npc.connection_lost")); return
        }
        remember()
        if (dialogue.isNotBlank() && !jbro.cobblemon.npc.dialogue.DialogueIds.isDialogueId(dialogue.trim())) {
            showResult(false, Component.translatable("message.cobblemon_npc.dialogue_id", dialogue))
            return
        }
        pending = Triple(name, skin, dialogue)
        closeAfterSave = close
        showResult(true, Component.translatable("screen.cobblemon_npc.saving"))
        children().filterIsInstance<net.minecraft.client.gui.components.AbstractWidget>().forEach { it.active = false }
        ClientPlayNetworking.send(NpcSavePayload(settings.entityId, name, skin, dialogue, false))
    }

    private fun editDialogue() {
        remember()
        val id = dialogue.trim()
        if (id.isEmpty()) {
            showResult(false, Component.translatable("screen.cobblemon_npc.dialogue_needed"))
            return
        }
        // Navigating must not silently persist the NPC's unsaved metadata.
        NpcClientState.editorParent = this
        ClientPlayNetworking.send(DialogueFetchPayload(id))
    }

    private fun remove() {
        remember()
        minecraft!!.setScreen(ConfirmScreen({ confirmed ->
            if (confirmed) {
                ClientPlayNetworking.send(NpcSavePayload(settings.entityId, name, skin, dialogue, true))
                minecraft!!.setScreen(null)
            } else minecraft!!.setScreen(this)
        }, Component.translatable("screen.cobblemon_npc.remove"), Component.translatable("screen.cobblemon_npc.remove_npc_hint")))
    }

    override fun onServerResult(ok: Boolean, key: String, args: List<String>) {
        super.onServerResult(ok, key, args)
        val submitted = pending ?: return
        if (ok && key == "message.cobblemon_npc.npc_saved") {
            saved = submitted; pending = null
            if (closeAfterSave) { minecraft?.setScreen(null); return }
        } else if (!ok) pending = null
        closeAfterSave = false
        if (pending == null) rebuildWidgets()
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        val state = if (pending != null) "saving" else if (Triple(name, skin, dialogue) != saved) "unsaved" else "saved"
        val top = ((height - 190) / 2).coerceAtLeast(4)
        graphics.drawCenteredString(font, Component.translatable("screen.cobblemon_npc.$state"), width / 2, top + 174, 0xC0C0C0)
    }

    override fun onClose() {
        remember()
        if (pending != null) { showResult(false, Component.translatable("screen.cobblemon_npc.wait_save")); return }
        if (Triple(name, skin, dialogue) != saved) minecraft?.setScreen(NpcUnsavedScreen(this, { save(true) }, { minecraft?.setScreen(null) }))
        else minecraft?.setScreen(null)
    }
}

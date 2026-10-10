package jbro.cobblemon.npc.client

import jbro.cobblemon.npc.network.DialogueFetchPayload
import jbro.cobblemon.npc.network.NpcSavePayload
import jbro.cobblemon.npc.network.NpcSettingsPayload
import jbro.cobblemon.uikit.CobblemonUiSharedTheme
import jbro.cobblemon.uikit.CobblemonUiThemes
import jbro.cobblemon.uikit.UiButtonSpec
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiControlSize
import jbro.cobblemon.uikit.UiPanelSpec
import jbro.cobblemon.uikit.UiWidthPolicy
import jbro.cobblemon.uikit.client.CobblemonUiButton
import jbro.cobblemon.uikit.client.CobblemonUiPanel
import jbro.cobblemon.uikit.client.UiTextRenderer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** What the operator screens share: the UI kit's shared theme while open, field labels, and a status line. */
abstract class NpcEditorScreen(title: Component) : Screen(title) {
    private var restoreTheme: (() -> Unit)? = null
    private val labels = mutableListOf<Triple<Component, Int, Int>>()
    protected var status: Component? = null
    protected var statusOk = true

    override fun init() {
        if (restoreTheme == null) restoreTheme = CobblemonUiSharedTheme.install()
        labels.clear()
    }

    override fun removed() {
        restoreTheme?.invoke()
        restoreTheme = null
    }

    override fun isPauseScreen() = false

    fun showResult(ok: Boolean, message: Component) {
        status = message
        statusOk = ok
    }

    protected fun label(text: Component, x: Int, y: Int) {
        labels += Triple(text, x, y)
    }

    protected fun field(x: Int, y: Int, width: Int, value: String, hint: Component, maxLength: Int = 256): EditBox {
        val box = EditBox(font, x, y, width, FIELD_HEIGHT, hint)
        box.setMaxLength(maxLength)
        box.value = value
        box.setHint(hint)
        return addRenderableWidget(box)
    }

    protected fun button(x: Int, y: Int, text: Component, variant: UiButtonVariant = UiButtonVariant.SECONDARY,
                         width: Int? = null, press: () -> Unit): CobblemonUiButton {
        val spec = UiButtonSpec(text, variant = variant, size = UiControlSize.SMALL,
            width = width?.let { UiWidthPolicy.Fixed(it) } ?: UiWidthPolicy.Content)
        return addRenderableWidget(CobblemonUiButton.create(x, y, width ?: 200, spec, press = press))
    }

    protected fun panel(x: Int, y: Int, width: Int, height: Int, title: Component) {
        addRenderableOnly(CobblemonUiPanel.create(x, y, width, height, UiPanelSpec(title = title, featured = true)))
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        val theme = CobblemonUiThemes.registry.snapshot()
        val ink = theme.surfaces.panelText ?: theme.colors.textPrimary
        labels.forEach { (text, x, y) -> UiTextRenderer.draw(graphics, font, text, x, y, ink) }
        status?.let {
            val color = if (statusOk) theme.colors.accentPrimary else 0xFFE05050.toInt()
            graphics.drawCenteredString(font, it, width / 2, height - 14, color)
        }
    }

    companion object {
        const val FIELD_HEIGHT = 18
    }
}

/** An NPC's name, skin and dialogue, opened by right-clicking it with the wand. */
class NpcSettingsScreen(private val settings: NpcSettingsPayload) :
    NpcEditorScreen(Component.translatable("screen.cobblemon_npc.settings")) {
    private var name = settings.name
    private var skin = settings.skin
    private var dialogue = settings.dialogue
    private var confirmRemove = false
    private lateinit var nameField: EditBox
    private lateinit var skinField: EditBox
    private lateinit var dialogueField: EditBox

    override fun init() {
        super.init()
        val panelWidth = 320.coerceAtMost(width - 16)
        val panelHeight = 168
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
        y += 26
        label(labelTexts[1], left + 14, y + 5)
        val pickWidth = 60
        skinField = field(fieldLeft, y, fieldWidth - pickWidth - 4, skin, Component.translatable("screen.cobblemon_npc.hint.skin"), 128)
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
        button(fieldLeft + fieldWidth - pickWidth, y, Component.translatable("screen.cobblemon_npc.pick_dialogue"), width = pickWidth) {
            remember()
            minecraft!!.setScreen(DialoguePickerScreen(this, settings.dialogues, dialogue) { picked ->
                dialogue = picked
                dialogueField.value = picked
            })
        }.active = settings.dialogues.isNotEmpty()
        y += 34

        val buttons = listOf(
            Triple(Component.translatable("screen.cobblemon_npc.save"), UiButtonVariant.PRIMARY) { save(false) },
            Triple(Component.translatable("screen.cobblemon_npc.edit_dialogue"), UiButtonVariant.SECONDARY) { editDialogue() },
            Triple(Component.translatable(if (confirmRemove) "screen.cobblemon_npc.remove_confirm" else "screen.cobblemon_npc.remove"),
                UiButtonVariant.DANGER) { remove() },
            Triple(Component.translatable("gui.done"), UiButtonVariant.GHOST) { onClose() },
        )
        val buttonWidth = (panelWidth - 28 - 3 * 6) / 4
        buttons.forEachIndexed { index, (text, variant, press) ->
            button(left + 14 + index * (buttonWidth + 6), y, text, variant, buttonWidth, press)
        }
        nameField.let(::setInitialFocus)
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

    private fun save(removing: Boolean) {
        remember()
        ClientPlayNetworking.send(NpcSavePayload(settings.entityId, name, skin, dialogue, removing))
    }

    private fun editDialogue() {
        remember()
        val id = dialogue.trim()
        if (id.isEmpty()) {
            showResult(false, Component.translatable("screen.cobblemon_npc.dialogue_needed"))
            return
        }
        // The NPC keeps the dialogue it is being written for.
        save(false)
        NpcClientState.editorParent = this
        ClientPlayNetworking.send(DialogueFetchPayload(id))
    }

    private fun remove() {
        if (!confirmRemove) {
            confirmRemove = true
            rebuildWidgets()
            return
        }
        save(true)
        onClose()
    }
}

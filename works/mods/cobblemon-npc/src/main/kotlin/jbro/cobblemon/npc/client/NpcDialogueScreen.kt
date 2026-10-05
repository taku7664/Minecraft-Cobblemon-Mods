package jbro.cobblemon.npc.client

import jbro.cobblemon.npc.network.DialogueAnswerPayload
import jbro.cobblemon.npc.network.DialogueLeavePayload
import jbro.cobblemon.npc.network.DialogueShowPayload
import jbro.cobblemon.ui.extended.CobblemonUiClient
import jbro.cobblemon.ui.extended.ui.shared.BattleUiSounds
import jbro.cobblemon.uikit.CobblemonUiSharedTheme
import jbro.cobblemon.uikit.CobblemonUiThemes
import jbro.cobblemon.uikit.UiBorder
import jbro.cobblemon.uikit.UiButtonSpec
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiControlSize
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.UiWidgetState
import jbro.cobblemon.uikit.UiWidthPolicy
import jbro.cobblemon.uikit.client.CobblemonUiButton
import jbro.cobblemon.uikit.client.UiSurfaceRenderer
import jbro.cobblemon.uikit.client.UiTextRenderer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.PlayerFaceRenderer
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import org.lwjgl.glfw.GLFW
import kotlin.math.sin

/**
 * An NPC's message box in the shared UI kit look, at the bottom of the screen like the games' own: each line is
 * written out, a click or the confirm key finishes it and then moves on, and the answers appear as buttons above the box
 * once the last line is read. The world keeps running behind it.
 */
class NpcDialogueScreen(private var show: DialogueShowPayload) : Screen(Component.literal(show.speaker)) {
    private var restoreTheme: (() -> Unit)? = null
    private var page = 0
    private var revealStart = System.nanoTime()
    private var waiting = false
    private var choicesShown = false
    private var closedByServer = false

    val session: Int get() = show.session

    /** The server moved on to [next] in the same talk. */
    fun update(next: DialogueShowPayload) {
        show = next
        page = 0
        waiting = false
        revealStart = System.nanoTime()
        choicesShown = false
        rebuildWidgets()
    }

    /** The server ended the talk; closing now must not report a leave back. */
    fun closeFromServer() {
        closedByServer = true
        onClose()
    }

    override fun init() {
        if (restoreTheme == null) restoreTheme = CobblemonUiSharedTheme.install()
        if (choicesShown) addChoices()
    }

    override fun removed() {
        restoreTheme?.invoke()
        restoreTheme = null
        if (!closedByServer) ClientPlayNetworking.send(DialogueLeavePayload(show.session))
    }

    override fun isPauseScreen() = false

    // The world stays in view: no blur or dimming behind the box.
    override fun renderBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {}

    override fun tick() {
        if (!choicesShown && lastPage() && revealed() && show.choices.isNotEmpty()) {
            choicesShown = true
            rebuildWidgets()
        }
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        drawBox(graphics)
        super.render(graphics, mouseX, mouseY, partialTick)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (super.mouseClicked(mouseX, mouseY, button)) return true
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) advance()
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (choicesShown) {
            val number = keyCode - GLFW.GLFW_KEY_1
            if (number in show.choices.indices) {
                choose(number)
                return true
            }
            return super.keyPressed(keyCode, scanCode, modifiers)
        }
        // The same confirm key the battle narration uses, set under the controls' Cobblemon Dialog UI.
        if (CobblemonUiClient.selectActionKey.matches(keyCode, scanCode)) {
            advance()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    private fun advance() {
        if (waiting) return
        if (!revealed()) {
            revealStart = 0L
            return
        }
        click()
        if (!lastPage()) {
            page++
            revealStart = System.nanoTime()
            return
        }
        if (show.choices.isNotEmpty()) return
        waiting = true
        ClientPlayNetworking.send(DialogueAnswerPayload(show.session, -1))
    }

    private fun choose(index: Int) {
        if (waiting) return
        waiting = true
        click()
        ClientPlayNetworking.send(DialogueAnswerPayload(show.session, index))
    }

    private fun addChoices() {
        val font = minecraft!!.font
        val box = boxRect()
        val theme = CobblemonUiThemes.registry.snapshot()
        val padding = theme.metrics(UiControlSize.MEDIUM).horizontalPadding
        val labels = show.choices.mapIndexed { index, text -> Component.literal("${index + 1}. $text") }
        val buttonWidth = (labels.maxOf(font::width) + padding * 2 + 8).coerceIn(80, box.width / 2)
        var bottom = box.y - 4
        labels.asReversed().forEachIndexed { reversed, label ->
            val index = labels.size - 1 - reversed
            val spec = UiButtonSpec(label, variant = UiButtonVariant.SECONDARY, width = UiWidthPolicy.Fixed(buttonWidth))
            val button = CobblemonUiButton.create(box.right - buttonWidth, 0, buttonWidth, spec, downSound = false) { choose(index) }
            bottom -= button.height
            button.y = bottom
            bottom -= 3
            addRenderableWidget(button)
        }
        children().firstOrNull()?.let(::setInitialFocus)
    }

    private fun boxRect(): UiRect {
        val width = (this.width * 0.73f).toInt().coerceIn(minOf(280, this.width - 24), 440)
        val height = BOX_HEIGHT.coerceAtMost(this.height - 8)
        return UiRect((this.width - width) / 2, (this.height - height - 6).coerceAtLeast(4), width, height)
    }

    private fun drawBox(graphics: GuiGraphics) {
        val font = minecraft!!.font
        val theme = CobblemonUiThemes.registry.snapshot()
        val ink = theme.surfaces.panelText ?: theme.colors.textPrimary
        val shadow = theme.listRowStyle(UiWidgetState.NORMAL).textShadowColor
        val box = boxRect()
        UiSurfaceRenderer.draw(graphics, box.x, box.y, box.width, box.height, theme.surfaces.panel)
        val inset = (theme.surfaces.panel.border as? UiBorder.WindowFrame)?.thickness ?: 2
        // The message window's bars at both ends, as the battle dialogue box draws them.
        graphics.fill(box.x + inset, box.y + inset, box.x + inset + END_BAR, box.bottom - inset, theme.colors.borderBright)
        graphics.fill(box.right - inset - END_BAR, box.y + inset, box.right - inset, box.bottom - inset, theme.colors.borderBright)

        val textLeft = box.x + PADDING
        val hasFace = show.skin.isNotBlank()
        if (show.speaker.isNotBlank() || hasFace) {
            // The speaker's face sits in the name plate, in front of the name.
            val name = Component.literal(show.speaker)
            val faceWidth = if (hasFace) FACE + if (show.speaker.isNotBlank()) FACE_GAP else 0 else 0
            val plateLeft = box.x + 8
            val plateWidth = faceWidth + font.width(name) + 16
            val plate = theme.style(UiButtonVariant.PRIMARY, UiWidgetState.NORMAL)
            UiSurfaceRenderer.draw(graphics, plateLeft, box.y - 14, plateWidth, 16, plate.surface)
            if (hasFace) PlayerFaceRenderer.draw(graphics, NpcSkins.skin(show.skin), plateLeft + 8, box.y - 12, FACE)
            UiTextRenderer.draw(graphics, font, name, plateLeft + 8 + faceWidth, box.y - 10, plate.text, plate.textShadowColor)
        }

        val line = show.lines.getOrElse(page) { "" }
        val wrapped = font.splitter.splitLines(line, box.right - PADDING - 12 - textLeft, Style.EMPTY).map { it.string }
        var budget = revealedCharacters()
        wrapped.forEachIndexed { index, text ->
            if (budget <= 0) return@forEachIndexed
            val shown = if (budget >= text.length) text else text.substring(0, budget)
            budget -= text.length
            UiTextRenderer.draw(graphics, font, Component.literal(shown), textLeft, box.y + 12 + index * LINE_HEIGHT, ink, shadow)
        }
        if (!revealed() || waiting || (lastPage() && show.choices.isNotEmpty())) return
        // The line is complete: a bobbing arrow says there is more to read.
        val arrowX = box.right - 20
        val baseY = box.bottom - 14 + (sin(System.nanoTime() / 180_000_000.0) * 1.5).toInt()
        for (row in 0 until 4) {
            graphics.fill(arrowX + row, baseY + row, arrowX + 7 - row, baseY + row + 1, theme.colors.accentPrimary)
        }
    }

    private fun lastPage() = page >= show.lines.size - 1

    private fun revealedCharacters(): Int =
        if (revealStart == 0L) Int.MAX_VALUE else ((System.nanoTime() - revealStart) / 1e9 * CHARACTERS_PER_SECOND).toInt()

    private fun revealed() = revealedCharacters() >= show.lines.getOrElse(page) { "" }.length

    private fun click() {
        // The battle message box's click, so a talk and a battle sound the same.
        BattleUiSounds.click()
    }

    companion object {
        private const val CHARACTERS_PER_SECOND = 45.0
        private const val BOX_HEIGHT = 66
        private const val PADDING = 14
        private const val FACE = 12
        private const val FACE_GAP = 4
        private const val END_BAR = 4
        private const val LINE_HEIGHT = 12
    }
}

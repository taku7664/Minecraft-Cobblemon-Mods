package jbro.cobblemon.npc.client

import jbro.cobblemon.npc.network.DialogueAnswerPayload
import jbro.cobblemon.npc.network.DialogueLeavePayload
import jbro.cobblemon.npc.network.DialogueShowPayload
import jbro.cobblemon.ui.extended.CinematicLetterbox
import jbro.cobblemon.ui.extended.CinematicScreen
import jbro.cobblemon.ui.extended.CobblemonUiClient
import jbro.cobblemon.ui.extended.ui.shared.BattleUiSounds
import jbro.cobblemon.uikit.CobblemonUiSharedTheme
import jbro.cobblemon.uikit.CobblemonUiThemes
import jbro.cobblemon.uikit.UiBorder
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.UiWidgetState
import jbro.cobblemon.uikit.client.CobblemonUiListRows
import jbro.cobblemon.uikit.client.UiListRowContent
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
 * written out, a click or the confirm key finishes it and then moves on, and once the last line is read the answers
 * appear above the box in one window, read as the MCC hub's tab rail reads: plain lines, the chosen one ringed by the
 * theme's cursor. The world keeps running behind it.
 */
class NpcDialogueScreen(private var show: DialogueShowPayload) : Screen(show.speaker), CinematicScreen {
    private var restoreTheme: (() -> Unit)? = null
    // The texts in this client's language; a translation key from the server is read here.
    private var speaker = show.speaker.string
    private var lines = show.lines.map(Component::getString)
    private var choices = show.choices.map(Component::getString)
    private var page = 0
    private var revealStart = System.nanoTime()
    private var waiting = false
    private var choicesShown = false
    private var cursor = 0
    private var closedByServer = false

    val session: Int get() = show.session

    /** The server moved on to [next] in the same talk. */
    fun update(next: DialogueShowPayload) {
        show = next
        speaker = next.speaker.string
        lines = next.lines.map(Component::getString)
        choices = next.choices.map(Component::getString)
        page = 0
        waiting = false
        revealStart = System.nanoTime()
        choicesShown = false
        cursor = 0
    }

    /** The server ended the talk; closing now must not report a leave back. */
    fun closeFromServer() {
        closedByServer = true
        onClose()
    }

    override fun init() {
        if (restoreTheme == null) {
            restoreTheme = CobblemonUiSharedTheme.install()
            // Once per opening (init also runs on resize): the battle camera looks at the speaker while the box is up.
            if (show.npcEntityId != DialogueShowPayload.NO_NPC) NpcDialogueCamera.focus(show.npcEntityId)
            CinematicLetterbox.show(LETTERBOX_OWNER)
        }
    }

    override fun removed() {
        restoreTheme?.invoke()
        restoreTheme = null
        NpcDialogueCamera.release()
        CinematicLetterbox.hide(LETTERBOX_OWNER)
        if (!closedByServer) ClientPlayNetworking.send(DialogueLeavePayload(show.session))
    }

    override fun isPauseScreen() = false

    // The world stays in view: no blur or dimming behind the box.
    override fun renderBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {}

    override fun tick() {
        if (!choicesShown && lastPage() && revealed() && choices.isNotEmpty()) {
            choicesShown = true
        }
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // A talk plays as a scene: the letterbox first, the dialogue box floating over it.
        CinematicLetterbox.renderBars(graphics, 0f)
        drawBox(graphics)
        if (choicesShown) drawChoices(graphics, mouseX, mouseY, partialTick)
        super.render(graphics, mouseX, mouseY, partialTick)
    }

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        // The cursor follows the mouse, as on the hub's rail.
        if (choicesShown) choiceAt(mouseX, mouseY)?.let { cursor = it }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true
        if (choicesShown) {
            choiceAt(mouseX, mouseY)?.let(::choose)
            return true
        }
        advance()
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (choicesShown) {
            val number = keyCode - GLFW.GLFW_KEY_1
            when {
                number in choices.indices -> choose(number)
                keyCode == GLFW.GLFW_KEY_UP -> moveCursor(-1)
                keyCode == GLFW.GLFW_KEY_DOWN -> moveCursor(1)
                CobblemonUiClient.selectActionKey.matches(keyCode, scanCode) -> choose(cursor)
                else -> return super.keyPressed(keyCode, scanCode, modifiers)
            }
            return true
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
        if (choices.isNotEmpty()) return
        waiting = true
        ClientPlayNetworking.send(DialogueAnswerPayload(show.session, -1))
    }

    private fun choose(index: Int) {
        if (waiting) return
        waiting = true
        click()
        ClientPlayNetworking.send(DialogueAnswerPayload(show.session, index))
    }

    private fun moveCursor(step: Int) {
        if (waiting) return
        cursor = Math.floorMod(cursor + step, choices.size)
        click()
    }

    /** The window that holds the answers, right-aligned just above the message box. */
    private fun choicesRect(): UiRect {
        val font = minecraft!!.font
        val box = boxRect()
        val width = (choices.maxOf(font::width) + ROW_TEXT_ROOM + CHOICES_INSET * 2).coerceIn(80, box.width / 2)
        val height = choices.size * CHOICE_ROW + (choices.size - 1) * CHOICE_GAP + CHOICES_INSET * 2
        return UiRect(box.right - width, box.y - 4 - height, width, height)
    }

    private fun choiceRow(index: Int): UiRect {
        val window = choicesRect()
        return UiRect(window.x + CHOICES_INSET, window.y + CHOICES_INSET + index * (CHOICE_ROW + CHOICE_GAP),
            window.width - CHOICES_INSET * 2, CHOICE_ROW)
    }

    private fun choiceAt(mouseX: Double, mouseY: Double): Int? = choices.indices.firstOrNull { index ->
        val row = choiceRow(index)
        mouseX >= row.x && mouseX < row.right && mouseY >= row.y && mouseY < row.bottom
    }

    private fun drawChoices(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val theme = CobblemonUiThemes.registry.snapshot()
        val window = choicesRect()
        UiSurfaceRenderer.draw(graphics, window.x, window.y, window.width, window.height, theme.surfaces.panel)
        choices.forEachIndexed { index, text ->
            // Rows in the theme's list style: unframed lines, the cursor's ring on the chosen one only.
            CobblemonUiListRows.draw(graphics, choiceRow(index), UiListRowContent(Component.literal(text), selected = index == cursor),
                enabled = !waiting, hovered = false, mouseX, mouseY, partialTick)
        }
    }

    private fun boxRect(): UiRect {
        val width = (this.width * 0.73f).toInt().coerceIn(minOf(280, this.width - 24), 440)
        val height = BOX_HEIGHT.coerceAtMost(this.height - 8)
        // Above the letterbox's bottom bar, never on it.
        val top = this.height - height - 6 - CinematicLetterbox.bottomInset(this.height)
        return UiRect((this.width - width) / 2, top.coerceAtLeast(4), width, height)
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
        if (speaker.isNotBlank() || hasFace) {
            // The speaker's face sits in the name plate, in front of the name.
            val name = Component.literal(speaker)
            val faceWidth = if (hasFace) FACE + if (speaker.isNotBlank()) FACE_GAP else 0 else 0
            val plateLeft = box.x + 8
            val plateWidth = faceWidth + font.width(name) + 16
            val plate = theme.style(UiButtonVariant.PRIMARY, UiWidgetState.NORMAL)
            UiSurfaceRenderer.draw(graphics, plateLeft, box.y - 14, plateWidth, 16, plate.surface)
            if (hasFace) PlayerFaceRenderer.draw(graphics, NpcSkins.skin(show.skin), plateLeft + 8, box.y - 12, FACE)
            UiTextRenderer.draw(graphics, font, name, plateLeft + 8 + faceWidth, box.y - 10, plate.text, plate.textShadowColor)
        }

        val line = lines.getOrElse(page) { "" }
        val wrapped = font.splitter.splitLines(line, box.right - PADDING - 12 - textLeft, Style.EMPTY).map { it.string }
        var budget = revealedCharacters()
        wrapped.forEachIndexed { index, text ->
            if (budget <= 0) return@forEachIndexed
            val shown = if (budget >= text.length) text else text.substring(0, budget)
            budget -= text.length
            UiTextRenderer.draw(graphics, font, Component.literal(shown), textLeft, box.y + 12 + index * LINE_HEIGHT, ink, shadow)
        }
        if (!revealed() || waiting || (lastPage() && choices.isNotEmpty())) return
        // The line is complete: a bobbing arrow says there is more to read.
        val arrowX = box.right - 20
        val baseY = box.bottom - 14 + (sin(System.nanoTime() / 180_000_000.0) * 1.5).toInt()
        for (row in 0 until 4) {
            graphics.fill(arrowX + row, baseY + row, arrowX + 7 - row, baseY + row + 1, theme.colors.accentPrimary)
        }
    }

    private fun lastPage() = page >= lines.size - 1

    private fun revealedCharacters(): Int =
        if (revealStart == 0L) Int.MAX_VALUE else ((System.nanoTime() - revealStart) / 1e9 * CHARACTERS_PER_SECOND).toInt()

    private fun revealed() = revealedCharacters() >= lines.getOrElse(page) { "" }.length

    private fun click() {
        // The battle message box's click, so a talk and a battle sound the same.
        BattleUiSounds.click()
    }

    companion object {
        private const val LETTERBOX_OWNER = "npc_dialogue"
        private const val CHARACTERS_PER_SECOND = 45.0
        private const val BOX_HEIGHT = 66
        private const val PADDING = 14
        private const val FACE = 12
        private const val FACE_GAP = 4
        private const val END_BAR = 4
        private const val LINE_HEIGHT = 12
        // The answers' window keeps the hub rail's room around its rows.
        private const val CHOICES_INSET = 6
        private const val CHOICE_ROW = 18
        private const val CHOICE_GAP = 3
        // A row's text starts 6 in and needs a little room after it.
        private const val ROW_TEXT_ROOM = 16
    }
}

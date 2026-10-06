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
import jbro.cobblemon.uikit.UiButtonSpec
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiControlSize
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.UiWidthPolicy
import jbro.cobblemon.uikit.client.CobblemonUiButton
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.PlayerFaceRenderer
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * An NPC's message box in the shared UI kit look, at the bottom of the screen like the games' own: each line is
 * written out, a click or the confirm key finishes it and then moves on, and the answers appear as buttons above the box
 * once the last line is read. The world keeps running behind it.
 */
class NpcDialogueScreen(private var show: DialogueShowPayload) : Screen(Component.literal(show.speaker)), CinematicScreen {
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
        if (restoreTheme == null) {
            restoreTheme = CobblemonUiSharedTheme.install()
            CinematicLetterbox.show(LETTERBOX_OWNER)
            // Once per opening (init also runs on resize): the battle camera looks at the speaker while the box is up.
            if (show.npcEntityId != DialogueShowPayload.NO_NPC) NpcDialogueCamera.focus(show.npcEntityId)
        }
        if (choicesShown) addChoices()
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
        if (!choicesShown && lastPage() && revealed() && show.choices.isNotEmpty()) {
            choicesShown = true
            rebuildWidgets()
        }
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // A talk plays as a scene: the letterbox, and the line read in its bottom bar beside the speaker's plate.
        CinematicLetterbox.renderBars(graphics)
        val hasFace = show.skin.isNotBlank()
        CinematicLetterbox.renderCaption(graphics,
            show.speaker.takeIf { it.isNotBlank() }?.let(Component::literal),
            if (hasFace) { g, x, y -> PlayerFaceRenderer.draw(g, NpcSkins.skin(show.skin), x, y, FACE) } else null,
            show.lines.getOrElse(page) { "" }, revealedCharacters(),
            more = !waiting && !(lastPage() && show.choices.isNotEmpty()))
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
        val box = answerColumn()
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

    /** The caption's column, ending above the letterbox's bottom bar: answers stack up from its right end. */
    private fun answerColumn(): UiRect {
        val width = (this.width * 0.78f).toInt().coerceIn(minOf(280, this.width - 24), 480)
        val bar = CinematicLetterbox.bottomBar(this.height)
        return UiRect((this.width - width) / 2, this.height - bar, width, bar)
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
        private const val LETTERBOX_OWNER = "npc_dialogue"
        private const val CHARACTERS_PER_SECOND = 45.0
        // The caption's plate leaves 10 GUI pixels for a face.
        private const val FACE = 10
    }
}

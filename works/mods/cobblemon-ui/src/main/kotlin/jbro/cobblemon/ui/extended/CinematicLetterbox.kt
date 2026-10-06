package jbro.cobblemon.ui.extended

import jbro.cobblemon.uikit.CobblemonUiSharedTheme
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiWidgetState
import jbro.cobblemon.uikit.client.UiSurfaceRenderer
import jbro.cobblemon.uikit.client.UiTextRenderer
import jbro.cobblemon.ui.extended.ui.shared.BattleFocusMotion
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import kotlin.math.roundToInt

/** A screen that draws the letterbox and its caption itself, so the shared overlay leaves it alone. */
interface CinematicScreen

/**
 * Black bars above and below the picture while a scene plays: a trainer's or an NPC's words with the camera on them.
 * Any number of owners may ask for the bars ([show]/[hide]); they slide in and out together. The words are read in
 * the bottom bar as a caption ([renderCaption]) rather than in a message box over the picture. Other UI makes room
 * where it can: battle HUD cards move below the top bar ([topInset]) and chat, toasts and name tags step aside.
 */
object CinematicLetterbox {
    private const val SLIDE_NANOS = 250_000_000L
    private const val BAR = 0xFF000000.toInt()
    private const val CAPTION_TEXT = 0xFFFFFFFF.toInt()
    private const val CAPTION_SHADOW = 0xFF000000.toInt()
    private const val CHIP = 0x33FFFFFF
    private const val GAP = 8
    private const val PAD = 6

    private val owners = LinkedHashSet<String>()
    private var progress = 0f
    private var lastNanos = 0L
    // Lines the caption needs, so the bottom bar grows to hold a long line.
    private var captionLines = 1

    internal fun register() {
        HudRenderCallback.EVENT.register { context, _ -> if (Minecraft.getInstance().screen == null) renderBars(context) }
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is CinematicScreen) ScreenEvents.afterRender(screen).register { _, context, _, _, _ -> renderBars(context) }
        }
    }

    @JvmStatic
    fun show(owner: String) {
        owners += owner
    }

    @JvmStatic
    fun hide(owner: String) {
        owners -= owner
        if (owners.isEmpty()) captionLines = 1
    }

    /** Bars are up or on their way, so UI that steps aside for them should. */
    @JvmStatic
    fun isActive(): Boolean = owners.isNotEmpty() || eased() > 0f

    /** How far the top bar reaches down now, in GUI pixels. */
    @JvmStatic
    fun topInset(screenHeight: Int): Int = (topBar(screenHeight) * eased()).roundToInt()

    /** How far the bottom bar reaches up now, in GUI pixels. */
    @JvmStatic
    fun bottomInset(screenHeight: Int): Int = (bottomBar(screenHeight) * eased()).roundToInt()

    /** The bottom bar's full height, for UI laid out above it (a talk's answers). */
    @JvmStatic
    fun bottomBar(screenHeight: Int): Int {
        val lineHeight = Minecraft.getInstance().font.lineHeight + 3
        return maxOf(topBar(screenHeight), captionLines * lineHeight + PAD * 2 + 2)
    }

    private fun topBar(screenHeight: Int): Int = (screenHeight * 0.11f).roundToInt().coerceIn(20, 48)

    private fun eased(): Float {
        val now = System.nanoTime()
        val step = if (lastNanos == 0L) 0f else (now - lastNanos).toFloat() / SLIDE_NANOS
        lastNanos = now
        val target = if (owners.isEmpty()) 0f else 1f
        progress = if (progress < target) minOf(target, progress + step) else maxOf(target, progress - step)
        return progress * progress * (3f - 2f * progress)
    }

    @JvmStatic
    fun renderBars(context: GuiGraphics) {
        val width = context.guiWidth()
        val height = context.guiHeight()
        val top = topInset(height)
        val bottom = bottomInset(height)
        if (top <= 0 && bottom <= 0) return
        context.pose().pushPose()
        // Over whatever the screen drew, under the caption.
        context.pose().translate(0f, 0f, 400f)
        context.fill(0, 0, width, top, BAR)
        context.fill(0, height - bottom, width, height, BAR)
        context.pose().popPose()
    }

    /**
     * The words being said, in the bottom bar: the speaker's name plate (with [face] drawn into it when given) on the
     * left and the line beside it, [shown] characters written out. With the line complete and [more] set, the confirm
     * key and a bobbing arrow say there is more to read.
     */
    @JvmStatic
    fun renderCaption(context: GuiGraphics, speaker: Component?, face: ((GuiGraphics, Int, Int) -> Unit)?,
                      line: String, shown: Int, more: Boolean) {
        val client = Minecraft.getInstance()
        val font = client.font
        val width = context.guiWidth()
        val height = context.guiHeight()
        val bar = bottomInset(height)
        if (bar <= 0) return
        val theme = CobblemonUiSharedTheme.snapshot()
        val lineHeight = font.lineHeight + 3
        val columnWidth = (width * 0.78f).toInt().coerceIn(minOf(280, width - 24), 480)
        val left = (width - columnWidth) / 2
        val top = height - bar + PAD
        context.pose().pushPose()
        context.pose().translate(0f, 0f, 410f)
        var textLeft = left
        if (speaker != null || face != null) {
            val plate = theme.style(UiButtonVariant.PRIMARY, UiWidgetState.NORMAL)
            val faceWidth = if (face != null) FACE + if (speaker != null) 4 else 0 else 0
            val plateWidth = faceWidth + (speaker?.let(font::width) ?: 0) + 12
            UiSurfaceRenderer.draw(context, left, top - 1, plateWidth, lineHeight + 2, plate.surface)
            face?.invoke(context, left + 6, top + (lineHeight - FACE) / 2)
            speaker?.let { UiTextRenderer.draw(context, font, it, left + 6 + faceWidth, top + 2, plate.text, plate.textShadowColor) }
            textLeft = left + plateWidth + GAP
        }
        val keyName = CobblemonUiClient.selectActionKey.translatedKeyMessage
        val tail = font.width(keyName) + 10 + 14
        val lines = font.splitter.splitLines(line, (left + columnWidth - tail - textLeft).coerceAtLeast(40), Style.EMPTY)
            .map { it.string }
        captionLines = lines.size.coerceIn(1, 4)
        var budget = shown
        lines.forEachIndexed { index, text ->
            if (budget <= 0) return@forEachIndexed
            val visible = if (budget >= text.length) text else text.substring(0, budget)
            budget -= text.length
            UiTextRenderer.draw(context, font, Component.literal(visible), textLeft, top + 2 + index * lineHeight, CAPTION_TEXT, CAPTION_SHADOW)
        }
        if (budget >= 0 && more) {
            val baseY = top + 2 + (lines.size - 1).coerceAtLeast(0) * lineHeight
            val arrowX = left + columnWidth - 8
            val chipX = arrowX - 6 - (font.width(keyName) + 10)
            context.fill(chipX, baseY - 2, chipX + font.width(keyName) + 10, baseY + 10, CHIP)
            UiTextRenderer.draw(context, font, keyName, chipX + 5, baseY, CAPTION_TEXT, CAPTION_SHADOW)
            val bob = (BattleFocusMotion.pulse() * 2f).toInt()
            for (row in 0 until 4) context.fill(arrowX + row, baseY + 1 + bob + row, arrowX + 7 - row, baseY + 2 + bob + row, CAPTION_TEXT)
        }
        context.pose().popPose()
    }

    private const val FACE = 10
}

package jbro.cobblemon.ui.extended

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import kotlin.math.roundToInt

/** A screen that draws the letterbox and its caption itself, so the shared overlay leaves it alone. */
interface CinematicScreen

/**
 * Black bars above and below the picture while a scene plays: a trainer's or an NPC's words with the camera on them.
 * Any number of owners may ask for the bars ([show]/[hide]); they slide in and out together. The dialogue box keeps
 * its look and floats over the bottom bar. Other UI makes room where it can: battle HUD cards move below the top bar
 * ([topInset]) and chat, toasts and name tags step aside.
 */
object CinematicLetterbox {
    // Long enough to read as a slide: the bars sweep in fast and settle.
    private const val SLIDE_NANOS = 450_000_000L
    private const val BAR = 0xFF000000.toInt()
    private const val BARS_Z = 400f
    /** The depth a dialogue box drawn over the bars translates to. */
    const val OVER_BARS_Z = 450f

    private val owners = LinkedHashSet<String>()
    private var progress = 0f
    private var lastNanos = 0L

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
    }

    /** Bars are up or on their way, so UI that steps aside for them should. */
    @JvmStatic
    fun isActive(): Boolean = owners.isNotEmpty() || eased() > 0f

    /** How far the top bar reaches down now, in GUI pixels. */
    @JvmStatic
    fun topInset(screenHeight: Int): Int = (bar(screenHeight) * eased()).roundToInt()

    /** How far the bottom bar reaches up now, in GUI pixels. */
    @JvmStatic
    fun bottomInset(screenHeight: Int): Int = (bar(screenHeight) * eased()).roundToInt()

    private fun bar(screenHeight: Int): Int = (screenHeight * 0.11f).roundToInt().coerceIn(20, 48)

    private fun eased(): Float {
        val now = System.nanoTime()
        val step = if (lastNanos == 0L) 0f else (now - lastNanos).toFloat() / SLIDE_NANOS
        lastNanos = now
        val target = if (owners.isEmpty()) 0f else 1f
        progress = if (progress < target) minOf(target, progress + step) else maxOf(target, progress - step)
        val rest = 1f - progress
        return 1f - rest * rest * rest
    }

    /** Over a screen's own drawing by default; a [CinematicScreen] draws them under its dialogue box at its own depth. */
    @JvmStatic
    @JvmOverloads
    fun renderBars(context: GuiGraphics, z: Float = BARS_Z) {
        val width = context.guiWidth()
        val height = context.guiHeight()
        val top = topInset(height)
        val bottom = bottomInset(height)
        if (top <= 0 && bottom <= 0) return
        context.pose().pushPose()
        context.pose().translate(0f, 0f, z)
        context.fill(0, 0, width, top, BAR)
        context.fill(0, height - bottom, width, height, BAR)
        context.pose().popPose()
    }

}

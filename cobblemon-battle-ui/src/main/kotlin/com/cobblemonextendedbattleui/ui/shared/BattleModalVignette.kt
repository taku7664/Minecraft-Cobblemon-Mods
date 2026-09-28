package jbro.cobblemon.battleui.extended.ui.shared

import jbro.cobblemon.battleui.navigation.VignetteFade
import net.minecraft.client.gui.DrawContext

/** Soft edge-only backdrop shared by switch, transcript, and battle-info modals. */
object BattleModalVignette {
    private const val INK = 0x040913
    private val fade = VignetteFade()

    @JvmStatic
    fun render(context: DrawContext, width: Int, height: Int, active: Boolean) {
        draw(context, width, height, fade.advance(active, System.nanoTime()))
    }

    /** Also used by the isolated visual previews at full opacity. */
    fun draw(context: DrawContext, width: Int, height: Int, opacity: Float) {
        if (width <= 0 || height <= 0 || opacity <= 0f) return
        val vertical = (height / 3).coerceAtLeast(1)
        val horizontal = (width / 4).coerceAtLeast(1)
        context.fillGradient(0, 0, width, vertical, ink(84, opacity), ink(0, opacity))
        context.fillGradient(0, height - vertical, width, height, ink(0, opacity), ink(84, opacity))
        // Draw narrow horizontal slices because DrawContext only exposes vertical gradients.
        for (index in 0 until 24) {
            val inner = horizontal * index / 24
            val outer = horizontal * (index + 1) / 24
            val alpha = ((1f - (index + .5f) / 24f) * 70).toInt()
            context.fill(inner, 0, outer, height, ink(alpha, opacity))
            context.fill(width - outer, 0, width - inner, height, ink(alpha, opacity))
        }
    }

    private fun ink(alpha: Int, opacity: Float): Int =
        ((alpha * opacity.coerceIn(0f, 1f)).toInt() shl 24) or INK
}

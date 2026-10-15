package jbro.cobblemon.uikit.client

import com.mojang.math.Axis
import jbro.cobblemon.uikit.UiRect
import net.minecraft.Util
import net.minecraft.client.gui.GuiGraphics
import kotlin.math.PI
import kotlin.math.sin

/**
 * A small icon's answer to the mouse arriving: it rocks a few degrees left and right and swells a little, then
 * settles back, once per hover. One instance per icon keeps when its hover began.
 */
class UiHoverWiggle {
    private var startedAt: Long? = null

    /** Draws [draw] over [rect], turned and scaled about its centre while the wiggle that [hovered] starts runs. */
    fun draw(graphics: GuiGraphics, rect: UiRect, hovered: Boolean, draw: () -> Unit) {
        val now = Util.getMillis()
        if (!hovered) startedAt = null else if (startedAt == null) startedAt = now
        val t = startedAt?.let { ((now - it) / DURATION_MS.toFloat()).coerceIn(0f, 1f) } ?: 1f
        if (t >= 1f) {
            draw()
            return
        }
        // The rocking fades as it goes; the swell rises and falls once.
        val angle = sin(t * PI * SWINGS * 2).toFloat() * MAX_DEGREES * (1f - t)
        val scale = 1f + MAX_GROWTH * sin(t * PI).toFloat()
        val pose = graphics.pose()
        pose.pushPose()
        pose.translate(rect.x + rect.width / 2f, rect.y + rect.height / 2f, 0f)
        pose.mulPose(Axis.ZP.rotationDegrees(angle))
        pose.scale(scale, scale, 1f)
        pose.translate(-(rect.x + rect.width / 2f), -(rect.y + rect.height / 2f), 0f)
        try { draw() } finally { pose.popPose() }
    }

    private companion object {
        const val DURATION_MS = 450L
        const val SWINGS = 2
        const val MAX_DEGREES = 12f
        const val MAX_GROWTH = 0.2f
    }
}

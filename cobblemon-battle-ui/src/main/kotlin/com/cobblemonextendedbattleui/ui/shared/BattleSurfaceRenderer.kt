package jbro.cobblemon.battleui.extended.ui.shared

import net.minecraft.client.gui.DrawContext
import kotlin.math.roundToInt

object BattleSurfaceRenderer {
    /** Each pixel is emitted once, so translucent borders never acquire fill underneath. */
    @JvmStatic
    fun draw(context: DrawContext, x: Int, y: Int, width: Int, height: Int, style: BattleSurface, opacity: Float = 1f) {
        if (width <= 0 || height <= 0 || opacity <= 0f) return
        val cut = minOf(style.cut, (width - 1) / 2, (height - 1) / 2)
        val border = style.borderWidth.coerceAtMost((minOf(width, height) + 1) / 2)
        val innerWidth = width - border * 2
        val innerHeight = height - border * 2
        for (row in 0 until height) {
            val left = inset(row, height, cut, style.corners and 1 != 0, style.corners and 8 != 0)
            val right = width - inset(row, height, cut, style.corners and 2 != 0, style.corners and 4 != 0)
            val edgeColor = withOpacity(style.border, opacity)
            if (border > 0 && (innerWidth <= 0 || innerHeight <= 0 || row < border || row >= height - border)) {
                context.fill(x + left, y + row, x + right, y + row + 1, edgeColor)
                continue
            }
            val innerCut = (cut - border).coerceAtLeast(0)
            val start = (border + inset(row - border, innerHeight, innerCut, style.corners and 1 != 0, style.corners and 8 != 0)).coerceIn(left, right)
            val end = (width - border - inset(row - border, innerHeight, innerCut, style.corners and 2 != 0, style.corners and 4 != 0)).coerceIn(start, right)
            if (start > left) context.fill(x + left, y + row, x + start, y + row + 1, edgeColor)
            if (end < right) context.fill(x + end, y + row, x + right, y + row + 1, edgeColor)
            val fill = interpolate(style.top, style.bottom, if (height == 1) 0f else row.toFloat() / (height - 1))
            val color = withOpacity(fill, opacity * style.backgroundOpacity)
            if (color ushr 24 != 0 && end > start) context.fill(x + start, y + row, x + end, y + row + 1, color)
        }
    }

    @JvmStatic
    fun inset(row: Int, height: Int, cut: Int, top: Boolean, bottom: Boolean): Int = maxOf(
        if (top) (cut - row).coerceAtLeast(0) else 0,
        if (bottom) (cut - (height - row - 1)).coerceAtLeast(0) else 0
    )

    @JvmStatic
    fun withOpacity(color: Int, opacity: Float): Int =
        (color and 0xFFFFFF) or (((color ushr 24) * opacity.coerceIn(0f, 1f)).roundToInt() shl 24)

    @JvmStatic
    fun interpolate(top: Int, bottom: Int, amount: Float): Int {
        var color = 0
        for (shift in intArrayOf(0, 8, 16, 24)) {
            val a = top ushr shift and 255
            val b = bottom ushr shift and 255
            color = color or ((a + (b - a) * amount.coerceIn(0f, 1f)).roundToInt() shl shift)
        }
        return color
    }
}

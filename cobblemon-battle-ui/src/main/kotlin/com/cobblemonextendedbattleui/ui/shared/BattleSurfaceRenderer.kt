package jbro.cobblemon.battleui.extended.ui.shared

import net.minecraft.client.gui.DrawContext
import kotlin.math.roundToInt

object BattleSurfaceRenderer {
    /** Each pixel is emitted once, so translucent borders never acquire fill underneath. */
    @JvmStatic
    fun draw(context: DrawContext, x: Int, y: Int, width: Int, height: Int, style: BattleSurface, opacity: Float = 1f) {
        if (width <= 0 || height <= 0 || opacity <= 0f) return
        val limit = minOf((width - 1) / 2, (height - 1) / 2)
        val cuts = style.cornerCuts ?: BattleCornerCuts(
            if (style.corners and 1 != 0) style.cut else 0,
            if (style.corners and 2 != 0) style.cut else 0,
            if (style.corners and 4 != 0) style.cut else 0,
            if (style.corners and 8 != 0) style.cut else 0)
        val tl = cuts.topLeft.coerceAtMost(limit)
        val tr = cuts.topRight.coerceAtMost(limit)
        val br = cuts.bottomRight.coerceAtMost(limit)
        val bl = cuts.bottomLeft.coerceAtMost(limit)
        val border = style.borderWidth.coerceAtMost((minOf(width, height) + 1) / 2)
        val innerWidth = width - border * 2
        val innerHeight = height - border * 2
        for (row in 0 until height) {
            val left = insetAsymmetric(row, height, tl, bl)
            val right = width - insetAsymmetric(row, height, tr, br)
            val edgeColor = withOpacity(style.border, opacity)
            if (border > 0 && (innerWidth <= 0 || innerHeight <= 0 || row < border || row >= height - border)) {
                context.fill(x + left, y + row, x + right, y + row + 1, edgeColor)
                continue
            }
            val start = (border + insetAsymmetric(row - border, innerHeight,
                (tl - border).coerceAtLeast(0), (bl - border).coerceAtLeast(0))).coerceIn(left, right)
            val end = (width - border - insetAsymmetric(row - border, innerHeight,
                (tr - border).coerceAtLeast(0), (br - border).coerceAtLeast(0))).coerceIn(start, right)
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
    fun insetAsymmetric(row: Int, height: Int, topCut: Int, bottomCut: Int): Int = maxOf(
        (topCut - row).coerceAtLeast(0),
        (bottomCut - (height - row - 1)).coerceAtLeast(0)
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

package jbro.cobblemon.ui.extended.ui.shared

import jbro.cobblemon.uikit.UiSmoothCorners
import net.minecraft.client.gui.GuiGraphics
import kotlin.math.roundToInt

object BattleSurfaceRenderer {
    /**
     * Straight edges and the inside are solid runs; each corner pixel is the mix of the border and fill covering it
     * (UI Kit's [UiSmoothCorners]), so rounded and cut corners are anti-aliased. Each pixel is emitted once, so
     * translucent borders never acquire fill underneath.
     */
    @JvmStatic
    fun draw(context: GuiGraphics, x: Int, y: Int, width: Int, height: Int, style: BattleSurface, opacity: Float = 1f) {
        if (width <= 0 || height <= 0 || opacity <= 0f) return
        val limit = if (style.rounded) minOf(width, height) / 2 else minOf((width - 1) / 2, (height - 1) / 2)
        val cuts = style.cornerCuts ?: BattleCornerCuts(
            if (style.corners and 1 != 0) style.cut else 0,
            if (style.corners and 2 != 0) style.cut else 0,
            if (style.corners and 4 != 0) style.cut else 0,
            if (style.corners and 8 != 0) style.cut else 0)
        val tl = cuts.topLeft.coerceIn(0, limit)
        val tr = cuts.topRight.coerceIn(0, limit)
        val br = cuts.bottomRight.coerceIn(0, limit)
        val bl = cuts.bottomLeft.coerceIn(0, limit)
        val border = style.borderWidth.coerceAtMost((minOf(width, height) + 1) / 2)
        val borderColor = withOpacity(style.border, opacity)
        val fillOpacity = opacity * style.backgroundOpacity
        val insets = if (border > 0) listOf(0, border) else listOf(0)
        fun layerColor(layer: Int, row: Int): Int =
            if (border > 0 && layer == 0) borderColor
            else withOpacity(interpolate(style.top, style.bottom, if (height == 1) 0f else row.toFloat() / (height - 1)), fillOpacity)
        val kind = if (style.rounded) UiSmoothCorners.Kind.ROUND else UiSmoothCorners.Kind.CUT
        fun coverage(size: Int) = if (size > 0) UiSmoothCorners.coverage(kind, size, insets) else emptyList()
        val topLeft = coverage(tl)
        val topRight = coverage(tr)
        val bottomRight = coverage(br)
        val bottomLeft = coverage(bl)

        for (row in 0 until height) {
            val depth = minOf(row, height - 1 - row)
            // Columns the corner boxes own on this row; the runs stay between them.
            val left = if (row < tl) tl else if (height - 1 - row < bl) bl else 0
            val right = width - if (row < tr) tr else if (height - 1 - row < br) br else 0
            insets.forEachIndexed { layer, inset ->
                if (depth < inset) return@forEachIndexed
                val color = layerColor(layer, row)
                val next = insets.getOrNull(layer + 1)
                if (next != null && depth >= next) {
                    run(context, x, y + row, maxOf(inset, left), minOf(next, right), color)
                    run(context, x, y + row, maxOf(width - next, left), minOf(width - inset, right), color)
                } else {
                    run(context, x, y + row, maxOf(inset, left), minOf(width - inset, right), color)
                }
            }
            if (left > 0) {
                val corner = if (row < tl) topLeft else bottomLeft
                val py = if (row < tl) row else height - 1 - row
                for (px in 0 until left) cornerPixel(context, x + px, y + row, corner, py * left + px, row, ::layerColor)
            }
            if (right < width) {
                val size = width - right
                val corner = if (row < tr) topRight else bottomRight
                val py = if (row < tr) row else height - 1 - row
                for (px in 0 until size) cornerPixel(context, x + width - 1 - px, y + row, corner, py * size + px, row, ::layerColor)
            }
        }
    }

    /**
     * A pill: its short side is a full half-circle at any size, odd widths included, so a 3 px scrollbar still gets
     * round ends. The straight middle is one run; each end pixel is covered by how much of it lies within half the
     * short side of the pill's spine (4x4 samples).
     */
    @JvmStatic
    fun capsule(context: GuiGraphics, x: Int, y: Int, width: Int, height: Int, color: Int, opacity: Float = 1f) {
        if (width <= 0 || height <= 0 || opacity <= 0f) return
        val argb = withOpacity(color, opacity)
        if (argb ushr 24 == 0) return
        val vertical = height > width
        val long = if (vertical) height else width
        val short = if (vertical) width else height
        val radius = short / 2f
        val cap = minOf(kotlin.math.ceil(radius).toInt(), long / 2)
        // The straight middle, between the two ends.
        if (long - cap * 2 > 0) {
            if (vertical) context.fill(x, y + cap, x + width, y + height - cap, argb)
            else context.fill(x + cap, y, x + width - cap, y + height, argb)
        }
        val alpha = argb ushr 24
        for (along in 0 until cap) for (across in 0 until short) {
            val covered = pillCoverage(along, across, long, short, radius)
            if (covered <= 0f) continue
            val pixel = (argb and 0xFFFFFF) or ((alpha * covered).roundToInt().coerceIn(0, 255) shl 24)
            // The same coverage serves both ends, mirrored along the pill.
            if (vertical) {
                context.fill(x + across, y + along, x + across + 1, y + along + 1, pixel)
                context.fill(x + across, y + height - 1 - along, x + across + 1, y + height - along, pixel)
            } else {
                context.fill(x + along, y + across, x + along + 1, y + across + 1, pixel)
                context.fill(x + width - 1 - along, y + across, x + width - along, y + across + 1, pixel)
            }
        }
    }

    /** Coverage of end pixel ([along], [across]) of a pill [long] by [short]: its distance to the spine within [radius]. */
    internal fun pillCoverage(along: Int, across: Int, long: Int, short: Int, radius: Float): Float {
        var inside = 0
        val spineStart = radius
        val spineEnd = long - radius
        for (sa in 0 until 4) for (sc in 0 until 4) {
            val u = along + (sa + .5f) / 4f
            val v = across + (sc + .5f) / 4f
            val du = u - u.coerceIn(spineStart, maxOf(spineStart, spineEnd))
            val dv = v - short / 2f
            if (du * du + dv * dv <= radius * radius) inside++
        }
        return inside / 16f
    }

    /** A rounded gauge: the track, then [ratio] of its inner width in [fill], both capsules. */
    @JvmStatic
    fun gauge(context: GuiGraphics, x: Int, y: Int, width: Int, height: Int, ratio: Float,
              track: Int, fill: Int, opacity: Float = 1f, inset: Int = 1) {
        capsule(context, x, y, width, height, track, opacity)
        val innerWidth = width - inset * 2
        val filled = (innerWidth * ratio.coerceIn(0f, 1f)).roundToInt()
        if (filled > 0) capsule(context, x + inset, y + inset, filled, height - inset * 2, fill, opacity)
    }

    /**
     * A soft halo around a rounded surface: [spread] one-pixel rings whose alpha falls off outward. Drawn before the
     * surface it surrounds, it reads as light rather than an outline.
     */
    @JvmStatic
    fun glow(context: GuiGraphics, x: Int, y: Int, width: Int, height: Int, corners: BattleCornerCuts,
             color: Int, spread: Int, opacity: Float) {
        if (opacity <= 0f || spread <= 0) return
        for (ring in 1..spread) {
            val falloff = 1f - (ring - 1f) / spread
            val ringCorners = BattleCornerCuts(grow(corners.topLeft, ring), grow(corners.topRight, ring),
                grow(corners.bottomRight, ring), grow(corners.bottomLeft, ring))
            draw(context, x - ring, y - ring, width + ring * 2, height + ring * 2,
                BattleSurface(0, 0, color, 1, cornerCuts = ringCorners, rounded = true), opacity * falloff * falloff)
        }
    }

    private fun grow(size: Int, by: Int) = if (size > 0) size + by else 0

    private fun run(context: GuiGraphics, x: Int, y: Int, start: Int, end: Int, color: Int) {
        if (end <= start || color ushr 24 == 0) return
        context.fill(x + start, y, x + end, y + 1, color)
    }

    /** One corner pixel: each layer weighted by how much of the pixel it covers, over transparency. */
    private inline fun cornerPixel(context: GuiGraphics, x: Int, y: Int, coverage: List<FloatArray>, index: Int,
                                   row: Int, color: (Int, Int) -> Int) {
        var alpha = 0f
        var red = 0f
        var green = 0f
        var blue = 0f
        for (layer in coverage.indices) {
            val covered = coverage[layer][index] - (coverage.getOrNull(layer + 1)?.get(index) ?: 0f)
            if (covered <= 0f) continue
            val argb = color(layer, row)
            val weight = covered * ((argb ushr 24) and 0xFF) / 255f
            alpha += weight
            red += weight * ((argb ushr 16) and 0xFF)
            green += weight * ((argb ushr 8) and 0xFF)
            blue += weight * (argb and 0xFF)
        }
        if (alpha <= 0.004f) return
        context.fill(x, y, x + 1, y + 1, ((alpha * 255f).roundToInt().coerceIn(0, 255) shl 24) or
            ((red / alpha).roundToInt().coerceIn(0, 255) shl 16) or
            ((green / alpha).roundToInt().coerceIn(0, 255) shl 8) or
            (blue / alpha).roundToInt().coerceIn(0, 255))
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

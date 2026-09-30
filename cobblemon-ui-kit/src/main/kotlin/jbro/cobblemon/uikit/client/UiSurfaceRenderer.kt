package jbro.cobblemon.uikit.client

import jbro.cobblemon.uikit.UiBorder
import jbro.cobblemon.uikit.UiCorner
import jbro.cobblemon.uikit.UiSmoothCorners
import jbro.cobblemon.uikit.UiFill
import jbro.cobblemon.uikit.UiSelectionIndicator
import jbro.cobblemon.uikit.UiShape
import jbro.cobblemon.uikit.UiSurfaceStyle
import jbro.cobblemon.uikit.horizontalSpan
import net.minecraft.client.gui.GuiGraphics
import kotlin.math.roundToInt

object UiSurfaceRenderer {
    fun draw(
        graphics: GuiGraphics,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        style: UiSurfaceStyle
    ) {
        if (width <= 0 || height <= 0) return
        val border = style.border
        if (border is UiBorder.PixelFrame && border.shadowOffset > 0) {
            drawFill(
                graphics,
                x + border.shadowOffset,
                y + border.shadowOffset,
                width,
                height,
                style.shape,
                UiFill.Solid(border.shadowColor),
                style.backgroundOpacity
            )
        }
        if (drawSmooth(graphics, x, y, width, height, style.shape, style.fill, style.backgroundOpacity, border)) return
        drawFill(graphics, x, y, width, height, style.shape, style.fill, style.backgroundOpacity)
        when (border) {
            UiBorder.None -> Unit
            is UiBorder.Solid -> drawBorder(graphics, x, y, width, height, style.shape, border)
            is UiBorder.PixelFrame -> drawPixelFrame(graphics, x, y, width, height, style.shape, border)
            is UiBorder.WindowFrame -> drawWindowFrame(graphics, x, y, width, height, style.shape, border)
        }
    }

    /**
     * Draws [indicator] over a widget drawn at these bounds in [shape]: an [UiSelectionIndicator.Outline] cursor
     * frame. Other indicators are the widget's own business and draw nothing here.
     */
    fun drawSelection(
        graphics: GuiGraphics,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        shape: UiShape,
        indicator: UiSelectionIndicator
    ) {
        if (indicator !is UiSelectionIndicator.Outline || width <= 0 || height <= 0) return
        val outline = UiBorder.Solid(indicator.color, indicator.width)
        if (drawSmooth(graphics, x, y, width, height, shape, UiFill.None, 1f, outline)) return
        drawBorder(graphics, x, y, width, height, shape, outline)
    }

    /**
     * The indicator to draw over a widget in [state]: a selected widget that is hovered, focused or pressed keeps
     * its [selected] cursor outline, so the choice stays visible under the mouse.
     */
    fun indicatorFor(state: UiSelectionIndicator, selected: UiSelectionIndicator?, isSelected: Boolean): UiSelectionIndicator =
        if (isSelected && state !is UiSelectionIndicator.Outline && selected is UiSelectionIndicator.Outline) selected else state

    /** One ring of a smooth surface: everything [inset] or more pixels in from the edge, up to the next ring. */
    private class Layer(val inset: Int, val color: (Int) -> Int)

    /**
     * Draws a rounded or chamfered surface with anti-aliased corners: straight edges and the inside as solid runs,
     * each corner pixel as the mix of the frame layers covering it (see [UiSmoothCorners]). Returns false, drawing
     * nothing, for shapes and frames it does not handle, which then take the stepped path.
     */
    private fun drawSmooth(
        graphics: GuiGraphics,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        shape: UiShape,
        fill: UiFill,
        opacity: Float,
        border: UiBorder
    ): Boolean {
        val size = UiSmoothCorners.cornerSize(shape, width, height)
        if (size <= 0) return false
        val fillColor: (Int) -> Int = when (fill) {
            UiFill.None -> { _ -> 0 }
            is UiFill.Solid -> { _ -> applyOpacity(fill.color, opacity) }
            is UiFill.VerticalGradient -> { row ->
                applyOpacity(interpolate(fill.topColor, fill.bottomColor, if (height == 1) 0f else row.toFloat() / (height - 1)), opacity)
            }
        }
        val layers = when (border) {
            UiBorder.None -> listOf(Layer(0, fillColor))
            is UiBorder.Solid -> listOf(Layer(0) { border.color }, Layer(border.width, fillColor))
            is UiBorder.WindowFrame -> buildList {
                add(Layer(0) { border.outerColor })
                add(Layer(1) { border.bandColor })
                border.innerColor?.let { inner -> add(Layer(1 + border.bandWidth) { inner }) }
                add(Layer(border.thickness, fillColor))
            }
            is UiBorder.PixelFrame -> return false
        }
        val insets = layers.map { it.inset }
        val corners = listOf(UiCorner.TOP_LEFT, UiCorner.TOP_RIGHT, UiCorner.BOTTOM_LEFT, UiCorner.BOTTOM_RIGHT)
            .associateWith { UiSmoothCorners.coverage(UiSmoothCorners.kind(shape, it), size, insets) }

        repeat(height) { row ->
            val depth = minOf(row, height - 1 - row)
            val cornerRow = row < size || row >= height - size
            // Columns the corner boxes own on this row; the runs stay between them.
            val left = if (cornerRow) size else 0
            val right = if (cornerRow) width - size else width
            layers.forEachIndexed { index, layer ->
                if (depth < layer.inset) return@forEachIndexed
                val color = layer.color(row)
                val next = layers.getOrNull(index + 1)?.inset
                if (next != null && depth >= next) {
                    fillRun(graphics, x, y + row, maxOf(layer.inset, left), minOf(next, right), color)
                    fillRun(graphics, x, y + row, maxOf(width - next, left), minOf(width - layer.inset, right), color)
                } else {
                    fillRun(graphics, x, y + row, maxOf(layer.inset, left), minOf(width - layer.inset, right), color)
                }
            }
            if (!cornerRow) return@repeat
            val top = row < size
            val py = if (top) row else height - 1 - row
            for (px in 0 until size) {
                drawCornerPixel(graphics, x + px, y + row, layers, row,
                    corners.getValue(if (top) UiCorner.TOP_LEFT else UiCorner.BOTTOM_LEFT), px, py, size)
                drawCornerPixel(graphics, x + width - 1 - px, y + row, layers, row,
                    corners.getValue(if (top) UiCorner.TOP_RIGHT else UiCorner.BOTTOM_RIGHT), px, py, size)
            }
        }
        return true
    }

    private fun fillRun(graphics: GuiGraphics, x: Int, y: Int, start: Int, end: Int, color: Int) {
        if (end <= start || color ushr 24 == 0) return
        graphics.fill(x + start, y, x + end, y + 1, color)
    }

    /** One corner pixel: each layer weighted by how much of the pixel it covers, premultiplied, over transparency. */
    private fun drawCornerPixel(
        graphics: GuiGraphics,
        x: Int,
        y: Int,
        layers: List<Layer>,
        row: Int,
        coverage: List<FloatArray>,
        px: Int,
        py: Int,
        size: Int
    ) {
        val index = py * size + px
        var alpha = 0f
        var red = 0f
        var green = 0f
        var blue = 0f
        layers.forEachIndexed { layer, spec ->
            val covered = coverage[layer][index] - (coverage.getOrNull(layer + 1)?.get(index) ?: 0f)
            if (covered <= 0f) return@forEachIndexed
            val color = spec.color(row)
            val weight = covered * ((color ushr 24) and 0xFF) / 255f
            alpha += weight
            red += weight * ((color ushr 16) and 0xFF)
            green += weight * ((color ushr 8) and 0xFF)
            blue += weight * (color and 0xFF)
        }
        if (alpha <= 0.004f) return
        val argb = ((alpha * 255f).roundToInt().coerceIn(0, 255) shl 24) or
            ((red / alpha).roundToInt().coerceIn(0, 255) shl 16) or
            ((green / alpha).roundToInt().coerceIn(0, 255) shl 8) or
            (blue / alpha).roundToInt().coerceIn(0, 255)
        graphics.fill(x, y, x + 1, y + 1, argb)
    }

    private fun drawWindowFrame(
        graphics: GuiGraphics,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        shape: UiShape,
        frame: UiBorder.WindowFrame
    ) {
        drawBorder(graphics, x, y, width, height, shape, UiBorder.Solid(frame.outerColor))
        if (width <= 2 || height <= 2) return
        drawBorder(graphics, x + 1, y + 1, width - 2, height - 2, shape.inset(1), UiBorder.Solid(frame.bandColor, frame.bandWidth))
        val inner = 1 + frame.bandWidth
        val innerColor = frame.innerColor ?: return
        if (width <= inner * 2 || height <= inner * 2) return
        drawBorder(graphics, x + inner, y + inner, width - inner * 2, height - inner * 2, shape.inset(inner), UiBorder.Solid(innerColor))
    }

    private fun drawPixelFrame(
        graphics: GuiGraphics,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        shape: UiShape,
        frame: UiBorder.PixelFrame
    ) {
        drawBorder(graphics, x, y, width, height, shape, UiBorder.Solid(frame.outerColor))
        if (width <= 4 || height <= 4) return

        val innerShape = shape.inset(1)
        drawBorder(
            graphics,
            x + 1,
            y + 1,
            width - 2,
            height - 2,
            innerShape,
            UiBorder.Solid(frame.highlightColor)
        )
        if (shape is UiShape.Rectangle) {
            graphics.fill(x + 2, y + height - 2, x + width - 1, y + height - 1, frame.shadeColor)
            graphics.fill(x + width - 2, y + 2, x + width - 1, y + height - 1, frame.shadeColor)
        }
    }

    private fun drawFill(
        graphics: GuiGraphics,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        shape: UiShape,
        fill: UiFill,
        opacity: Float
    ) {
        if (fill is UiFill.None || opacity <= 0f) return
        repeat(height) { row ->
            val span = shape.horizontalSpan(width, height, row)
            val color = when (fill) {
                UiFill.None -> return@repeat
                is UiFill.Solid -> fill.color
                is UiFill.VerticalGradient -> interpolate(
                    fill.topColor,
                    fill.bottomColor,
                    if (height == 1) 0f else row.toFloat() / (height - 1)
                )
            }
            graphics.fill(
                x + span.start,
                y + row,
                x + span.endExclusive,
                y + row + 1,
                applyOpacity(color, opacity)
            )
        }
    }

    private fun drawBorder(
        graphics: GuiGraphics,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        shape: UiShape,
        border: UiBorder.Solid
    ) {
        val inset = border.width.coerceAtMost(minOf(width / 2, height / 2))
        val innerWidth = width - inset * 2
        val innerHeight = height - inset * 2
        val innerShape = shape.inset(inset)

        repeat(height) { row ->
            val outer = shape.horizontalSpan(width, height, row)
            if (inset == 0 || innerWidth <= 0 || innerHeight <= 0 || row < inset || row >= height - inset) {
                graphics.fill(x + outer.start, y + row, x + outer.endExclusive, y + row + 1, border.color)
                return@repeat
            }

            val inner = innerShape.horizontalSpan(innerWidth, innerHeight, row - inset)
            val innerStart = (inset + inner.start).coerceIn(outer.start, outer.endExclusive)
            val innerEnd = (inset + inner.endExclusive).coerceIn(innerStart, outer.endExclusive)
            if (innerStart > outer.start) {
                graphics.fill(x + outer.start, y + row, x + innerStart, y + row + 1, border.color)
            }
            if (innerEnd < outer.endExclusive) {
                graphics.fill(x + innerEnd, y + row, x + outer.endExclusive, y + row + 1, border.color)
            }
        }
    }

    private fun UiShape.inset(pixels: Int): UiShape = when (this) {
        UiShape.Rectangle -> UiShape.Rectangle
        UiShape.Circle -> UiShape.Circle
        UiShape.Capsule -> UiShape.Capsule
        UiShape.Diamond -> UiShape.Diamond
        is UiShape.RoundedRectangle -> {
            val innerRadius = radiusPixels - pixels
            if (innerRadius > 0) UiShape.RoundedRectangle(innerRadius) else UiShape.Rectangle
        }
        is UiShape.Chamfer -> {
            val innerCut = cutPixels - pixels
            if (innerCut > 0) UiShape.Chamfer(innerCut, corners) else UiShape.Rectangle
        }
    }

    private fun applyOpacity(color: Int, opacity: Float): Int {
        val alpha = (((color ushr 24) and 0xFF) * opacity).roundToInt().coerceIn(0, 255)
        return color and 0x00FFFFFF or (alpha shl 24)
    }

    private fun interpolate(from: Int, to: Int, amount: Float): Int {
        fun channel(shift: Int): Int {
            val start = from ushr shift and 0xFF
            val end = to ushr shift and 0xFF
            return (start + (end - start) * amount).roundToInt().coerceIn(0, 255)
        }
        return channel(24) shl 24 or
            (channel(16) shl 16) or
            (channel(8) shl 8) or
            channel(0)
    }
}

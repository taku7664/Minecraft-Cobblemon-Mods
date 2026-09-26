package jbro.cobblemon.uikit.client

import jbro.cobblemon.uikit.UiBorder
import jbro.cobblemon.uikit.UiFill
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
        drawFill(graphics, x, y, width, height, style.shape, style.fill, style.backgroundOpacity)
        when (border) {
            UiBorder.None -> Unit
            is UiBorder.Solid -> drawBorder(graphics, x, y, width, height, style.shape, border)
            is UiBorder.PixelFrame -> drawPixelFrame(graphics, x, y, width, height, style.shape, border)
        }
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

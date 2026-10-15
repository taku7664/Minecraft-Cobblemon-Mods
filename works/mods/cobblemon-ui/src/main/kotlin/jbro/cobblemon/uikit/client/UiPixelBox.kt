package jbro.cobblemon.uikit.client

import jbro.cobblemon.uikit.UiBorder
import jbro.cobblemon.uikit.UiFill
import jbro.cobblemon.uikit.UiSelectionIndicator
import jbro.cobblemon.uikit.UiShape
import jbro.cobblemon.uikit.UiSurfaceStyle
import jbro.cobblemon.uikit.UiThemeSnapshot
import net.minecraft.client.gui.GuiGraphics

/**
 * Small framed boxes a screen draws for itself (tabs, route nodes, check boxes, portrait frames) in the shape of the
 * theme's panels, drawn by [UiSurfaceRenderer] as panels and buttons are, smoothed corners included.
 */
object UiPixelBox {
    /**
     * A box over [x], [y], [width], [height]: a one-pixel [border] and [fill] inside it, or the border alone when
     * [fill] is null. [open] paints over the bottom edge with [fill], for a tab opening into the page under it.
     */
    fun draw(graphics: GuiGraphics, theme: UiThemeSnapshot, x: Int, y: Int, width: Int, height: Int, border: Int,
        fill: Int?, open: Boolean = false) {
        if (width <= 0 || height <= 0) return
        UiSurfaceRenderer.draw(graphics, x, y, width, height,
            UiSurfaceStyle(shape(theme, width, height), fill?.let(UiFill::Solid) ?: UiFill.None, UiBorder.Solid(border)))
        if (open && fill != null) graphics.fill(x + 1, y + height - 1, x + width - 1, y + height, fill)
    }

    /** A ring [thickness] pixels wide around the inside of the bounds, in the panels' shape. */
    fun ring(graphics: GuiGraphics, theme: UiThemeSnapshot, x: Int, y: Int, width: Int, height: Int, color: Int, thickness: Int) =
        UiSurfaceRenderer.drawSelection(graphics, x, y, width, height, shape(theme, width, height),
            UiSelectionIndicator.Outline(color, thickness))

    /** The panels' shape, its rounding scaled down for a small box: a check box gets a pixel, a node box three. */
    fun shape(theme: UiThemeSnapshot, width: Int, height: Int): UiShape = when (val shape = theme.surfaces.panel.shape) {
        is UiShape.RoundedRectangle -> {
            val radius = minOf(shape.radiusPixels, minOf(width, height) / 5)
            if (radius > 0) UiShape.RoundedRectangle(radius) else UiShape.Rectangle
        }
        else -> shape
    }
}

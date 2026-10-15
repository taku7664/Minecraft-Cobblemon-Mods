package jbro.cobblemon.uikit.client

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence

/**
 * Text as the UI kit draws it: with a theme's coloured one-pixel drop shadow when [shadowColor] is set (the pale
 * shadow of DS menu text), otherwise flat or with Minecraft's own shadow when [minecraftShadow] asks for it.
 */
object UiTextRenderer {
    /**
     * The y to draw one line of [text] at so the ink its glyphs draw sits centred in [height] rows from [top], an odd
     * spare row going below. Glyphs of different fonts (Minecraft's Latin bitmap, a Hangul font from a resource pack)
     * put their ink at different heights in the same line, so centring on the line height leaves some text a pixel low.
     */
    fun centeredY(font: Font, text: FormattedCharSequence, top: Int, height: Int): Int {
        val ink = inkRows(font, text) ?: return top + (height - font.lineHeight) / 2 + 1
        return top + Math.floorDiv(height - (ink.last - ink.first + 1), 2) - ink.first
    }

    fun centeredY(font: Font, text: Component, top: Int, height: Int): Int = centeredY(font, text.visualOrderText, top, height)

    /** The rows below the draw y that [text]'s glyphs put ink on, or null for text with no visible glyph. */
    fun inkRows(font: Font, text: FormattedCharSequence): IntRange? {
        var up = Float.MAX_VALUE
        var down = -Float.MAX_VALUE
        text.accept { _, style, codepoint ->
            val glyph = font.getFontSet(style.font).getGlyph(codepoint)
            if (glyph.down > glyph.up) {
                up = minOf(up, glyph.up)
                down = maxOf(down, glyph.down)
            }
            true
        }
        if (up > down) return null
        return kotlin.math.floor(up).toInt() until kotlin.math.ceil(down).toInt()
    }

    /** [text] as it is, or cut with an ellipsis to fit [width]. */
    fun fitted(font: Font, text: Component, width: Int): Component {
        if (font.width(text) <= width) return text
        return Component.literal(font.plainSubstrByWidth(text.string, (width - font.width("…")).coerceAtLeast(0)) + "…")
    }

    fun draw(
        graphics: GuiGraphics,
        font: Font,
        text: Component,
        x: Int,
        y: Int,
        color: Int,
        shadowColor: Int? = null,
        minecraftShadow: Boolean = false
    ) {
        if (shadowColor != null) {
            graphics.drawString(font, text, x + 1, y + 1, shadowColor, false)
            graphics.drawString(font, text, x, y, color, false)
        } else {
            graphics.drawString(font, text, x, y, color, minecraftShadow)
        }
    }

    fun draw(
        graphics: GuiGraphics,
        font: Font,
        text: FormattedCharSequence,
        x: Int,
        y: Int,
        color: Int,
        shadowColor: Int? = null,
        minecraftShadow: Boolean = false
    ) {
        if (shadowColor != null) {
            graphics.drawString(font, text, x + 1, y + 1, shadowColor, false)
            graphics.drawString(font, text, x, y, color, false)
        } else {
            graphics.drawString(font, text, x, y, color, minecraftShadow)
        }
    }
}

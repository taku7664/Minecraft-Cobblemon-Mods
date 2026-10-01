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

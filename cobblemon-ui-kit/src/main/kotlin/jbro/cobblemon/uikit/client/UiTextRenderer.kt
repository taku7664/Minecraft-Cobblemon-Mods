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

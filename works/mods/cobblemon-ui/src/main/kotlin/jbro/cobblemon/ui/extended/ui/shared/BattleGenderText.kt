package jbro.cobblemon.ui.extended.ui.shared

import com.cobblemon.mod.common.pokemon.Gender
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics

/** Keeps the name and colored sex marker together while reserving the marker's width. */
object BattleGenderText {
    fun draw(context: GuiGraphics, name: String, gender: Gender?, x: Int, y: Int,
             maxWidth: Int, opacity: Float, nameColor: Int = BattleUiTheme.TEXT) {
        val font = Minecraft.getInstance().font
        val symbol = when (gender) {
            Gender.MALE -> "♂"
            Gender.FEMALE -> "♀"
            else -> ""
        }
        val symbolWidth = if (symbol.isEmpty()) 0 else font.width(symbol) + 1
        val visibleName = font.plainSubstrByWidth(name, (maxWidth - symbolWidth).coerceAtLeast(0))
        context.drawString(font, visibleName, x, y,
            BattleSurfaceRenderer.withOpacity(nameColor, opacity), false)
        if (symbol.isNotEmpty() && maxWidth >= symbolWidth) {
            val color = if (gender == Gender.MALE) BattleUiTheme.MALE else BattleUiTheme.FEMALE
            context.drawString(font, symbol, x + font.width(visibleName) + 1, y,
                BattleSurfaceRenderer.withOpacity(color, opacity), false)
        }
    }
}

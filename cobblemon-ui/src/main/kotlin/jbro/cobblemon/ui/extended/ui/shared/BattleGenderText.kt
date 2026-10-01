package jbro.cobblemon.ui.extended.ui.shared

import com.cobblemon.mod.common.pokemon.Gender
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext

/** Keeps the name and colored sex marker together while reserving the marker's width. */
object BattleGenderText {
    fun draw(context: DrawContext, name: String, gender: Gender?, x: Int, y: Int,
             maxWidth: Int, opacity: Float, nameColor: Int = BattleUiTheme.TEXT) {
        val font = MinecraftClient.getInstance().textRenderer
        val symbol = when (gender) {
            Gender.MALE -> "♂"
            Gender.FEMALE -> "♀"
            else -> ""
        }
        val symbolWidth = if (symbol.isEmpty()) 0 else font.getWidth(symbol) + 1
        val visibleName = font.trimToWidth(name, (maxWidth - symbolWidth).coerceAtLeast(0))
        context.drawText(font, visibleName, x, y,
            BattleSurfaceRenderer.withOpacity(nameColor, opacity), false)
        if (symbol.isNotEmpty() && maxWidth >= symbolWidth) {
            val color = if (gender == Gender.MALE) BattleUiTheme.MALE else BattleUiTheme.FEMALE
            context.drawText(font, symbol, x + font.getWidth(visibleName) + 1, y,
                BattleSurfaceRenderer.withOpacity(color, opacity), false)
        }
    }
}

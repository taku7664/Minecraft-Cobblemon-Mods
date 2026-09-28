package jbro.cobblemon.battleui.extended.ui.shared

import com.cobblemon.mod.common.pokemon.Gender
import com.cobblemon.mod.common.api.pokemon.status.Statuses
import jbro.cobblemon.battleui.extended.pokemon.render.PokemonModelRenderer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import java.util.UUID

/** Development-only layout study. No live HUD or input path calls this renderer. */
internal object BattleHudSplitDraft {
    private data class Preview(val species: String, val gender: Gender, val hp: Float,
                               val health: String, val burned: Boolean = false)

    fun render(context: DrawContext, width: Int, korean: Boolean) {
        val left = Preview("pikachu", Gender.MALE, .72f, "86/120")
        val right = Preview("charizard", Gender.FEMALE, .36f, "36%", burned = true)
        val leftCompact = Preview("bulbasaur", Gender.FEMALE, .91f, "91/100")
        val rightCompact = Preview("venusaur", Gender.MALE, .54f, "54%", burned = true)
        draw(context, 12, 36, left, true, false, korean)
        draw(context, width - 155, 36, right, false, false, korean)
        draw(context, 12, 103, leftCompact, true, true, korean)
        draw(context, width - 135, 103, rightCompact, false, true, korean)
        draw(context, 12, 143, left, true, true, korean)
        draw(context, width - 135, 143, right, false, true, korean)
    }

    private fun draw(context: DrawContext, x: Int, y: Int, pokemon: Preview,
                     ally: Boolean, compact: Boolean, korean: Boolean) {
        val portraitSize = if (compact) 24 else 30
        val infoWidth = if (compact) 96 else 110
        val infoHeight = if (compact) 24 else 30
        val gap = 3
        val portraitX = if (ally) x else x + infoWidth + gap
        val infoX = if (ally) x + portraitSize + gap else x
        val accent = if (ally) BattleUiTheme.CYAN else BattleUiTheme.PURPLE
        BattleSurfaceRenderer.draw(context, portraitX, y, portraitSize, portraitSize,
            BattleUiTheme.panel.copy(border = accent, borderWidth = 1,
                cornerCuts = if (ally) BattleCornerCuts(topLeft = 5, bottomRight = 5)
                    else BattleCornerCuts(topRight = 5, bottomLeft = 5)))
        BattleSurfaceRenderer.draw(context, infoX, y, infoWidth, infoHeight,
            BattleUiTheme.panel.copy(borderWidth = 0,
                cornerCuts = if (ally) BattleCornerCuts(topLeft = 3, bottomRight = if (compact) 5 else 7)
                    else BattleCornerCuts(topRight = 3, bottomLeft = if (compact) 5 else 7)))
        PokemonModelRenderer.drawPokemonModel(context, portraitX + 2, y + 2, portraitSize - 4,
            null, Identifier.of("cobblemon", pokemon.species), emptySet(),
            UUID.nameUUIDFromBytes(pokemon.species.toByteArray()), false, null, ally, { it }, 1f)

        val font = MinecraftClient.getInstance().textRenderer
        val name = Text.translatable("cobblemon.species.${pokemon.species}.name").string
        val maxNameWidth = infoWidth - 39
        val symbolWidth = font.getWidth(if (pokemon.gender == Gender.MALE) "♂" else "♀") + 1
        val trimmedName = font.trimToWidth(name, maxNameWidth - symbolWidth)
        val nameWidth = font.getWidth(trimmedName) + symbolWidth
        val nameX = if (ally) infoX + 6 else infoX + infoWidth - 6 - nameWidth
        BattleGenderText.draw(context, name, pokemon.gender, nameX, y + 3, maxNameWidth, 1f)
        val level = "50"
        val levelX = if (ally) infoX + infoWidth - 6 - font.getWidth(level) else infoX + 6
        context.drawText(font, level, levelX, y + 3, BattleUiTheme.MUTED, false)

        val barWidth = if (compact) 43 else 56
        val barX = if (ally) infoX + 6 else infoX + infoWidth - 6 - barWidth
        val barY = y + if (compact) 16 else 18
        context.fill(barX, barY, barX + barWidth, barY + 4, BattleUiTheme.TRACK)
        val hpColor = when {
            pokemon.hp > .5f -> BattleUiTheme.GOOD
            pokemon.hp > .25f -> BattleUiTheme.FOCUS
            else -> BattleUiTheme.DANGER
        }
        context.fill(barX + 1, barY + 1,
            barX + 1 + ((barWidth - 2) * pokemon.hp).toInt(), barY + 3, hpColor)
        val numberX = if (ally) infoX + infoWidth - 6 - font.getWidth(pokemon.health) else infoX + 6
        context.drawText(font, pokemon.health, numberX, barY - 3, BattleUiTheme.TEXT, false)

        if (pokemon.burned) {
            val label = if (korean) "화상" else "BRN"
            val badgeWidth = font.getWidth(label) + 4
            val badgeX = if (ally) infoX else infoX + infoWidth - badgeWidth
            val badgeY = y + infoHeight + 3
            context.fill(badgeX, badgeY, badgeX + badgeWidth, badgeY + 9,
                BattleStatusPalette.background(Statuses.BURN.showdownName))
            context.drawText(font, label, badgeX + 2, badgeY, 0xFF182337.toInt(), false)
        }
    }
}

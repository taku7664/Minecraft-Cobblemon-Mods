package jbro.cobblemon.battleui.extended.ui.shared

import com.cobblemon.mod.common.pokemon.Gender
import jbro.cobblemon.battleui.extended.ui.transcript.TranscriptPortraits
import jbro.cobblemon.battleui.extended.ui.transcript.TranscriptSpeaker
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import java.util.UUID

/** Edge-anchored HUD study; only the opt-in development capture screen calls this. */
internal object BattleHudEdgeDraft {
    private const val WIDTH = 132
    private const val HEIGHT = 25
    private const val ROW_STEP = 28
    private data class Pokemon(val species: String, val gender: Gender, val hp: Float,
                               val health: String, val status: String? = null)

    fun render(context: DrawContext, screenWidth: Int, korean: Boolean) {
        val allies = listOf(
            Pokemon("pikachu", Gender.MALE, .72f, "86/120"),
            Pokemon("bulbasaur", Gender.FEMALE, .91f, "91/100"),
            Pokemon("eevee", Gender.FEMALE, .48f, "48/100")
        )
        val opponents = listOf(
            Pokemon("charizard", Gender.FEMALE, .36f, "36%", "brn"),
            Pokemon("venusaur", Gender.MALE, .54f, "54%", "par"),
            Pokemon("blastoise", Gender.MALE, .67f, "67%")
        )
        allies.forEachIndexed { index, pokemon -> draw(context, 0, 23 + index * ROW_STEP, pokemon, true, korean) }
        opponents.forEachIndexed { index, pokemon ->
            draw(context, screenWidth - WIDTH, 23 + index * ROW_STEP, pokemon, false, korean)
        }
    }

    private fun draw(context: DrawContext, x: Int, y: Int, pokemon: Pokemon, ally: Boolean, korean: Boolean) {
        BattleSurfaceRenderer.draw(context, x, y, WIDTH, HEIGHT,
            BattleUiTheme.panel.copy(top = 0xE5284054.toInt(), bottom = 0xE70C192B.toInt(),
                borderWidth = 0, cornerCuts = if (ally) BattleCornerCuts(bottomRight = 9)
                    else BattleCornerCuts(bottomLeft = 9)))
        val accent = if (ally) BattleUiTheme.CYAN else BattleUiTheme.PURPLE
        val accentX = if (ally) x + WIDTH - 3 else x
        context.fill(accentX, y + 2, accentX + 3, y + 14, accent)

        val portraitX = if (ally) x + 2 else x + WIDTH - 24
        val speaker = TranscriptSpeaker(UUID.nameUUIDFromBytes("hud-edge-${pokemon.species}-$ally".toByteArray()),
            ally, "", Text.empty(), Identifier.of("cobblemon", pokemon.species), emptySet())
        if (!TranscriptPortraits.draw(context, speaker, portraitX, y + 1, 22)) {
            val font = MinecraftClient.getInstance().textRenderer
            context.drawText(font, "?", portraitX + 7, y + 7, BattleUiTheme.MUTED, false)
        }

        val font = MinecraftClient.getInstance().textRenderer
        val contentX = if (ally) x + 29 else x + 12
        val contentRight = if (ally) x + WIDTH - 12 else x + WIDTH - 29
        val name = Text.translatable("cobblemon.species.${pokemon.species}.name").string
        BattleGenderText.draw(context, name, pokemon.gender, contentX, y + 2,
            contentRight - contentX - font.getWidth("Lv.50") - 4, 1f)
        val level = "Lv.50"
        context.drawText(font, level, contentRight - font.getWidth(level), y + 2, BattleUiTheme.MUTED, false)
        val barWidth = 45
        val barY = y + 17
        context.fill(contentX, barY, contentX + barWidth, barY + 4, BattleUiTheme.TRACK)
        val hpColor = when {
            pokemon.hp > .5f -> BattleUiTheme.GOOD
            pokemon.hp > .25f -> BattleUiTheme.FOCUS
            else -> BattleUiTheme.DANGER
        }
        context.fill(contentX + 1, barY + 1,
            contentX + 1 + ((barWidth - 2) * pokemon.hp).toInt(), barY + 3, hpColor)
        context.drawText(font, pokemon.health, contentRight - font.getWidth(pokemon.health),
            y + 14, BattleUiTheme.TEXT, false)

        pokemon.status?.let { status ->
            val label = when (status) {
                "brn" -> if (korean) "화상" else "BRN"
                "par" -> if (korean) "마비" else "PAR"
                else -> status.uppercase()
            }
            val badgeWidth = font.getWidth(label) + 4
            val badgeX = if (ally) x + WIDTH + 2 else x - badgeWidth - 2
            context.fill(badgeX, y + 14, badgeX + badgeWidth, y + 23,
                BattleStatusPalette.background(status))
            context.drawText(font, label, badgeX + 2, y + 14, 0xFF182337.toInt(), false)
        }
    }
}

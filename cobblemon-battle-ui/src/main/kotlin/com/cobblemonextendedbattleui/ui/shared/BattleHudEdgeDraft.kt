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
    private const val WIDTH = 136
    private const val HEIGHT = 25
    private const val ROW_STEP = 28
    private const val END_SLOPE = 12
    private data class Pokemon(val species: String, val gender: Gender, val hp: Float,
                               val health: String, val experience: Float? = null,
                               val status: String? = null)

    fun render(context: DrawContext, screenWidth: Int, korean: Boolean) {
        val allies = listOf(
            Pokemon("pikachu", Gender.MALE, .72f, "86/120", .38f, "par"),
            Pokemon("bulbasaur", Gender.FEMALE, .91f, "91/100", .72f),
            Pokemon("eevee", Gender.FEMALE, .48f, "48/100", .54f)
        )
        val opponents = listOf(
            Pokemon("charizard", Gender.FEMALE, .36f, "36%", status = "brn"),
            Pokemon("venusaur", Gender.MALE, .54f, "54%", status = "par"),
            Pokemon("blastoise", Gender.MALE, .67f, "67%")
        )
        allies.forEachIndexed { index, pokemon -> draw(context, 0, 23 + index * ROW_STEP, pokemon, true, korean) }
        opponents.forEachIndexed { index, pokemon ->
            draw(context, screenWidth - WIDTH, 23 + index * ROW_STEP, pokemon, false, korean)
        }
    }

    private fun draw(context: DrawContext, x: Int, y: Int, pokemon: Pokemon, ally: Boolean, korean: Boolean) {
        drawSlantedPanel(context, x, y, ally)

        val portraitX = if (ally) x + 2 else x + WIDTH - 24
        val speaker = TranscriptSpeaker(UUID.nameUUIDFromBytes("hud-edge-${pokemon.species}-$ally".toByteArray()),
            ally, "", Text.empty(), Identifier.of("cobblemon", pokemon.species), emptySet())
        if (!TranscriptPortraits.draw(context, speaker, portraitX, y + 1, 22)) {
            val font = MinecraftClient.getInstance().textRenderer
            context.drawText(font, "?", portraitX + 7, y + 7, BattleUiTheme.MUTED, false)
        }

        val font = MinecraftClient.getInstance().textRenderer
        val contentX = if (ally) x + 29 else x + 16
        val contentRight = if (ally) x + WIDTH - 16 else x + WIDTH - 29
        val name = Text.translatable("cobblemon.species.${pokemon.species}.name").string
        BattleGenderText.draw(context, name, pokemon.gender, contentX, y + 2,
            contentRight - contentX - font.getWidth("Lv.50") - 4, 1f)
        val level = "Lv.50"
        context.drawText(font, level, contentRight - font.getWidth(level), y + 2, BattleUiTheme.MUTED, false)
        val barWidth = 45
        val barY = y + 14
        context.fill(contentX, barY, contentX + barWidth, barY + 4, BattleUiTheme.TRACK)
        val hpColor = when {
            pokemon.hp > .5f -> BattleUiTheme.GOOD
            pokemon.hp > .25f -> BattleUiTheme.FOCUS
            else -> BattleUiTheme.DANGER
        }
        context.fill(contentX + 1, barY + 1,
            contentX + 1 + ((barWidth - 2) * pokemon.hp).toInt(), barY + 3, hpColor)
        pokemon.experience?.let { progress ->
            val expWidth = 31
            val expX = contentX + barWidth - expWidth
            context.fill(expX, y + 21, expX + expWidth, y + 23, BattleUiTheme.TRACK)
            context.fill(expX, y + 21, expX + (expWidth * progress.coerceIn(0f, 1f)).toInt(),
                y + 23, 0xFF62BFEF.toInt())
        }
        context.drawText(font, pokemon.health, contentRight - font.getWidth(pokemon.health),
            y + 14, BattleUiTheme.TEXT, false)

        pokemon.status?.let { status ->
            val label = when (status) {
                "brn" -> if (korean) "화상" else "BRN"
                "par" -> if (korean) "마비" else "PAR"
                else -> status.uppercase()
            }
            val badgeWidth = font.getWidth(label) + 4
            val badgeX = if (ally) x + WIDTH - 8 else x - badgeWidth + 8
            val badgeY = y + HEIGHT - 9
            context.fill(badgeX, badgeY, badgeX + badgeWidth, badgeY + 9,
                BattleStatusPalette.background(status))
            context.drawText(font, label, badgeX + 2, badgeY, 0xFF182337.toInt(), false)
        }
    }

    /** A full-height diagonal; BattleSurfaceRenderer intentionally caps corner cuts at half-height. */
    private fun drawSlantedPanel(context: DrawContext, x: Int, y: Int, ally: Boolean) {
        for (row in 0 until HEIGHT) {
            val offset = row * END_SLOPE / (HEIGHT - 1)
            val start = if (ally) x else x + END_SLOPE - offset
            val end = if (ally) x + WIDTH - END_SLOPE + offset else x + WIDTH
            val color = BattleSurfaceRenderer.interpolate(
                0xE5284054.toInt(), 0xE70C192B.toInt(), row.toFloat() / (HEIGHT - 1))
            context.fill(start, y + row, end, y + row + 1, color)
        }
    }
}

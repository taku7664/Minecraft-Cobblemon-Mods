package jbro.cobblemon.battleui.extended.ui.shared

import com.cobblemon.mod.common.pokemon.Gender
import jbro.cobblemon.battleui.extended.ui.transcript.TranscriptPortraits
import jbro.cobblemon.battleui.extended.ui.transcript.TranscriptSpeaker
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import java.util.UUID

/** The same edge card is used by the sample-data draft and native battle HUD. */
internal object BattleHudCardRenderer {
    const val WIDTH = 136
    const val HEIGHT = 25
    private const val END_SLOPE = 12
    private const val BAR_WIDTH = 45
    private const val EXP_WIDTH = 31
    private val emptyName = Text.empty()
    private val caughtIndicator = Identifier.of("cobblemon", "textures/gui/battle/battle_owned_indicator.png")

    data class Card(
        val uuid: UUID,
        val species: Identifier,
        val aspects: Set<String>,
        val name: String,
        val gender: Gender,
        val level: Int,
        val hp: Float,
        val health: String,
        val experience: Float? = null,
        val status: String? = null,
        val owned: Boolean = false,
        val actorName: Text? = null,
        val selected: Boolean = false,
        val hovered: Boolean = false,
        val opacity: Float = 1f
    )

    fun draw(context: DrawContext, x: Int, y: Int, ally: Boolean, card: Card) {
        drawSlantedPanel(context, x, y, ally, card)
        val font = MinecraftClient.getInstance().textRenderer
        val portraitX = if (ally) x + 2 else x + WIDTH - 24
        val speaker = TranscriptSpeaker(card.uuid, ally, "", emptyName, card.species, card.aspects)
        if (!TranscriptPortraits.draw(context, speaker, portraitX, y + 1, 22)) {
            context.drawText(font, "?", portraitX + 7, y + 7,
                BattleSurfaceRenderer.withOpacity(BattleUiTheme.MUTED, card.opacity), false)
        }

        val contentX = if (ally) x + 29 else x + 16
        val contentRight = if (ally) x + WIDTH - 16 else x + WIDTH - 29
        val level = "Lv.${card.level}"
        BattleGenderText.draw(context, card.name, card.gender, contentX, y + 2,
            contentRight - contentX - font.getWidth(level) - 4, card.opacity)
        val muted = BattleSurfaceRenderer.withOpacity(BattleUiTheme.MUTED, card.opacity)
        context.drawText(font, level, contentRight - font.getWidth(level), y + 2, muted, false)

        val ratio = card.hp.coerceIn(0f, 1f)
        val barY = y + 14
        context.fill(contentX, barY, contentX + BAR_WIDTH, barY + 4,
            BattleSurfaceRenderer.withOpacity(BattleUiTheme.TRACK, card.opacity))
        val hpColor = when {
            ratio > .5f -> BattleUiTheme.GOOD
            ratio > .25f -> BattleUiTheme.FOCUS
            else -> BattleUiTheme.DANGER
        }
        context.fill(contentX + 1, barY + 1,
            contentX + 1 + ((BAR_WIDTH - 2) * ratio).toInt(), barY + 3,
            BattleSurfaceRenderer.withOpacity(hpColor, card.opacity))
        card.experience?.let { progress ->
            val expX = contentX + BAR_WIDTH - EXP_WIDTH
            context.fill(expX, y + 21, expX + EXP_WIDTH, y + 23,
                BattleSurfaceRenderer.withOpacity(BattleUiTheme.TRACK, card.opacity))
            context.fill(expX, y + 21, expX + (EXP_WIDTH * progress.coerceIn(0f, 1f)).toInt(),
                y + 23, BattleSurfaceRenderer.withOpacity(0xFF62BFEF.toInt(), card.opacity))
        }
        context.drawText(font, card.health, contentRight - font.getWidth(card.health), y + 14,
            BattleSurfaceRenderer.withOpacity(BattleUiTheme.TEXT, card.opacity), false)

        card.status?.let { status ->
            val key = status.takeIf { it in setOf("brn", "par", "psn", "tox", "slp", "frz") } ?: "other"
            val label = Text.translatable("cobblemon_battle_ui.switch.status.$key").string
            val badgeWidth = font.getWidth(label) + 4
            val badgeX = if (ally) x + WIDTH - 8 else x - badgeWidth + 8
            val badgeY = y + HEIGHT - 9
            context.fill(badgeX, badgeY, badgeX + badgeWidth, badgeY + 9,
                BattleSurfaceRenderer.withOpacity(BattleStatusPalette.background(status), card.opacity))
            context.drawText(font, label, badgeX + 2, badgeY,
                BattleSurfaceRenderer.withOpacity(0xFF182337.toInt(), card.opacity), false)
        }
        if (card.owned) {
            val indicatorX = if (ally) x + 2 else x + WIDTH - 10
            context.drawTexture(caughtIndicator, indicatorX, y + 1,
                0f, 0f, 10, 10, 10, 10)
        }
        card.actorName?.let { actor ->
            context.drawText(font, font.trimToWidth(actor.string, WIDTH), x, y - 9, muted, false)
        }
    }

    private fun drawSlantedPanel(context: DrawContext, x: Int, y: Int, ally: Boolean, card: Card) {
        val top = if (card.selected || card.hovered) 0xE53B536A.toInt() else 0xE5284054.toInt()
        for (row in 0 until HEIGHT) {
            val offset = row * END_SLOPE / (HEIGHT - 1)
            val start = if (ally) x else x + END_SLOPE - offset
            val end = if (ally) x + WIDTH - END_SLOPE + offset else x + WIDTH
            val color = BattleSurfaceRenderer.interpolate(top, 0xE70C192B.toInt(), row.toFloat() / (HEIGHT - 1))
            context.fill(start, y + row, end, y + row + 1,
                BattleSurfaceRenderer.withOpacity(color, card.opacity))
        }
    }
}

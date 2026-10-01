package jbro.cobblemon.ui.extended.ui.shared

import com.cobblemon.mod.common.pokemon.Gender
import jbro.cobblemon.ui.extended.ui.transcript.TranscriptPortraits
import jbro.cobblemon.ui.extended.ui.transcript.TranscriptSpeaker
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import java.util.UUID

/** The same edge card is used by the sample-data draft and native battle HUD. */
internal object BattleHudCardRenderer {
    const val WIDTH = 136
    const val HEIGHT = 25
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
        val hpColor = when {
            ratio > .5f -> BattleUiTheme.GOOD
            ratio > .25f -> BattleUiTheme.FOCUS
            else -> BattleUiTheme.DANGER
        }
        BattleSurfaceRenderer.gauge(context, contentX, barY, BAR_WIDTH, 5, ratio,
            BattleUiTheme.TRACK, hpColor, card.opacity)
        card.experience?.let { progress ->
            val expX = contentX + BAR_WIDTH - EXP_WIDTH
            BattleSurfaceRenderer.gauge(context, expX, y + 21, EXP_WIDTH, 3, progress,
                BattleUiTheme.TRACK, 0xFF62BFEF.toInt(), card.opacity, inset = 0)
        }
        context.drawText(font, card.health, contentRight - font.getWidth(card.health), y + 14,
            BattleSurfaceRenderer.withOpacity(BattleUiTheme.TEXT, card.opacity), false)

        card.status?.let { status ->
            val key = status.takeIf { it in setOf("brn", "par", "psn", "tox", "slp", "frz") } ?: "other"
            val label = Text.translatable("cobblemon_ui.switch.status.$key").string
            val badgeWidth = font.getWidth(label) + 4
            val badgeX = if (ally) x + WIDTH - 8 else x - badgeWidth + 8
            val badgeY = y + HEIGHT - 9
            BattleSurfaceRenderer.capsule(context, badgeX - 2, badgeY, badgeWidth + 4, 9,
                BattleStatusPalette.background(status), card.opacity)
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

    /**
     * The card is flush with its screen edge and rounded at its inner end: a large radius at the top, a small one at
     * the bottom, so it keeps the old slope's direction. A band in the side's color follows the inner end's curve.
     */
    private fun drawSlantedPanel(context: DrawContext, x: Int, y: Int, ally: Boolean, card: Card) {
        val focused = card.selected || card.hovered
        val top = if (focused) 0xF23B536A.toInt() else 0xF2284054.toInt()
        val edge = if (focused) BattleUiTheme.FOCUS else if (ally) BattleUiTheme.CYAN else BattleUiTheme.PURPLE
        val corners = if (ally) BattleCornerCuts(topRight = TOP_RADIUS, bottomRight = BOTTOM_RADIUS)
            else BattleCornerCuts(topLeft = TOP_RADIUS, bottomLeft = BOTTOM_RADIUS)
        val bandReach = TOP_RADIUS + ACCENT_WIDTH
        BattleSurfaceRenderer.draw(context, if (ally) x + WIDTH - bandReach else x, y, bandReach, HEIGHT,
            BattleSurface(edge, edge, cornerCuts = corners), card.opacity * .8f)
        BattleSurfaceRenderer.draw(context, if (ally) x else x + ACCENT_WIDTH, y, WIDTH - ACCENT_WIDTH, HEIGHT,
            BattleSurface(top, 0xF20C192B.toInt(), cornerCuts = corners), card.opacity)
    }

    private const val TOP_RADIUS = 11
    private const val BOTTOM_RADIUS = 5
    private const val ACCENT_WIDTH = 3
}

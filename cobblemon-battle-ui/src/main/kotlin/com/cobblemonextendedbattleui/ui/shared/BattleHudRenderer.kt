package jbro.cobblemon.battleui.extended.ui.shared

import com.cobblemon.mod.common.api.pokedex.PokedexEntryProgress
import com.cobblemon.mod.common.pokemon.Gender
import com.cobblemon.mod.common.pokemon.Species
import com.cobblemon.mod.common.pokemon.status.PersistentStatus
import com.cobblemon.mod.common.client.render.models.blockbench.PosableState
import com.cobblemon.mod.common.client.gui.battle.BattleOverlay
import com.cobblemon.mod.common.client.CobblemonClient
import jbro.cobblemon.battleui.extended.pokemon.render.PokemonModelRenderer
import jbro.cobblemon.battleui.navigation.BattleScreenGeometry
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import java.util.UUID
import kotlin.math.ceil

/** Replaces only the ordinary HUD tile; Cobblemon still owns capture-ball animation. */
object BattleHudRenderer {
    private const val WIDTH = 184
    private val caughtIndicator = Identifier.of("cobblemon", "textures/gui/battle/battle_owned_indicator.png")

    @JvmStatic
    fun draw(context: DrawContext, nativeX: Float, nativeY: Float, reversed: Boolean, species: Species,
             level: Int, displayName: Text, gender: Gender, status: PersistentStatus?,
             state: PosableState, opacity: Float, maxHealth: Int, health: Float,
             selected: Boolean, hovered: Boolean, compact: Boolean,
             actorName: Text?, flatHealth: Boolean, dexState: PokedexEntryProgress) {
        val client = MinecraftClient.getInstance()
        val nativeWidth = if (compact) BattleOverlay.COMPACT_TILE_WIDTH else BattleOverlay.TILE_WIDTH
        val battleType = CobblemonClient.battle?.battleFormat?.battleType
        val slotIndent = if (compact && battleType != null) {
            BattleScreenGeometry.compactHudSlotIndent(nativeY,
                battleType.slotsPerActor, battleType.actorsPerSide)
        } else 0
        val x = (nativeX + if (reversed) 2 - (WIDTH - nativeWidth) + slotIndent else -2 - slotIndent).toInt()
        val y = nativeY.toInt() + if (compact) 12 else 18
        val height = if (compact) 28 else 50
        val accent = if (reversed) BattleUiTheme.PURPLE else BattleUiTheme.CYAN
        BattleSurfaceRenderer.draw(context, x, y, WIDTH, height,
            BattleUiTheme.panel.copy(top = 0xE01A3045.toInt(), bottom = 0xDC0D1A2B.toInt(),
                border = if (selected || hovered) BattleUiTheme.FOCUS else accent,
                borderWidth = if (selected || hovered) 2 else 1, cut = 5,
                corners = if (reversed) 0b0110 else 0b1001), opacity)

        val portraitSize = if (compact) 22 else 30
        val portraitX = if (reversed) x + WIDTH - portraitSize - 7 else x + 7
        val portraitY = y + if (compact) 3 else 6
        BattleSurfaceRenderer.draw(context, portraitX, portraitY, portraitSize, portraitSize,
            BattleUiTheme.panel.copy(border = accent, cut = 4,
                corners = if (reversed) 0b0110 else 0b1001), opacity)
        val ratio = (if (flatHealth) health / maxHealth.coerceAtLeast(1) else health).coerceIn(0f, 1f)
        val stableId = UUID.nameUUIDFromBytes("${species.resourceIdentifier}-$reversed-${displayName.string}".toByteArray())
        PokemonModelRenderer.drawPokemonModel(context, portraitX + 2, portraitY + 2, portraitSize - 4,
            null, species.resourceIdentifier, state.currentAspects, stableId,
            ratio <= 0f, status, !reversed,
            { BattleSurfaceRenderer.withOpacity(it, opacity) }, 1f)

        val contentX = if (reversed) x + 9 else x + portraitSize + 13
        val contentRight = if (reversed) portraitX - 7 else x + WIDTH - 10
        val font = client.textRenderer
        val textColor = BattleSurfaceRenderer.withOpacity(BattleUiTheme.TEXT, opacity)
        val muted = BattleSurfaceRenderer.withOpacity(BattleUiTheme.MUTED, opacity)
        val statusLabel = status?.let {
            val key = it.showdownName.takeIf { name -> name in setOf("brn", "par", "psn", "tox", "slp", "frz") } ?: "other"
            Text.translatable("cobblemon_battle_ui.switch.status.$key").string
        }
        if (compact) {
            val suffixWidth = if (statusLabel == null) 26 else 55
            context.drawText(font, font.trimToWidth(displayName.string, contentRight - contentX - suffixWidth),
                contentX, y + 3, textColor, false)
            if (statusLabel != null) rightText(context, font.trimToWidth(statusLabel, 26), contentRight - 24,
                y + 3, BattleSurfaceRenderer.withOpacity(BattleUiTheme.DANGER, opacity))
            rightText(context, "$level", contentRight, y + 3, muted)
            drawHp(context, contentX, contentRight - if (flatHealth) 43 else 31,
                y + 17, ratio, opacity)
            rightText(context, if (flatHealth) "${health.toInt()}/$maxHealth" else "${ceil(ratio * 100).toInt()}%",
                contentRight, y + 16, textColor)
        } else {
            val role = Text.translatable(if (reversed) "cobblemon_battle_ui.hud.opponent" else "cobblemon_battle_ui.hud.self")
            context.drawText(font, role, contentX, y + 4, BattleSurfaceRenderer.withOpacity(accent, opacity), false)
            if (statusLabel != null) rightText(context, font.trimToWidth(statusLabel, 35), contentRight,
                y + 4, BattleSurfaceRenderer.withOpacity(BattleUiTheme.DANGER, opacity))
            val genderSymbol = when (gender) {
                Gender.MALE -> "♂"
                Gender.FEMALE -> "♀"
                else -> ""
            }
            val name = displayName.string + genderSymbol
            context.drawText(font, font.trimToWidth(name, contentRight - contentX - 30), contentX,
                y + 15, textColor, false)
            rightText(context, "$level", contentRight, y + 15, muted)
            drawHp(context, contentX, contentRight, y + 31, ratio, opacity)
            context.drawText(font, "HP", contentX, y + 40, muted, false)
            rightText(context, if (flatHealth) "${health.toInt()}/$maxHealth" else "${ceil(ratio * 100).toInt()}%",
                contentRight, y + 40, textColor)
        }
        if (dexState == PokedexEntryProgress.OWNED) {
            context.matrices.push()
            context.matrices.translate((x + if (reversed) 3 else WIDTH - 8).toFloat(), (y + 3).toFloat(), 0f)
            context.matrices.scale(.5f, .5f, 1f)
            context.drawTexture(caughtIndicator, 0, 0, 0f, 0f, 10, 10, 10, 10)
            context.matrices.pop()
        }
        if (actorName != null) context.drawText(font, font.trimToWidth(actorName.string, WIDTH), x,
            y - 9, muted, false)
    }

    private fun drawHp(context: DrawContext, left: Int, right: Int, y: Int, ratio: Float, opacity: Float) {
        context.fill(left, y, right, y + 6, BattleSurfaceRenderer.withOpacity(BattleUiTheme.TRACK, opacity))
        val color = when {
            ratio > .5f -> BattleUiTheme.GOOD
            ratio > .25f -> BattleUiTheme.FOCUS
            else -> BattleUiTheme.DANGER
        }
        context.fill(left + 1, y + 1, left + 1 + ((right - left - 2) * ratio).toInt(), y + 5,
            BattleSurfaceRenderer.withOpacity(color, opacity))
    }

    private fun rightText(context: DrawContext, text: String, right: Int, y: Int, color: Int) {
        val font = MinecraftClient.getInstance().textRenderer
        context.drawText(font, text, right - font.getWidth(text), y, color, false)
    }
}

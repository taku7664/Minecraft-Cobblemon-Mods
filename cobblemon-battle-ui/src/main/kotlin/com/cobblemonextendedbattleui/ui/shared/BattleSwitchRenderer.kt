package jbro.cobblemon.battleui.extended.ui.shared

import com.cobblemon.mod.common.api.pokemon.status.Status
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection
import jbro.cobblemon.battleui.extended.navigation.KeyboardTileFocus
import jbro.cobblemon.battleui.extended.pokemon.render.PokemonModelRenderer
import jbro.cobblemon.battleui.navigation.BattleScreenGeometry
import jbro.cobblemon.battleui.navigation.SwitchHealth
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import java.util.UUID

data class BattlePartyCard(
    val species: Identifier,
    val aspects: Set<String>,
    val uuid: UUID,
    val name: Text,
    val level: Int,
    val healthRatio: Float,
    val fainted: Boolean,
    val active: Boolean,
    val status: Status?,
    val statusLabel: Text?
)

/** The party preview and the live Cobblemon switch selection share this visual path. */
object BattleSwitchRenderer {
    @JvmStatic
    fun drawSelection(context: DrawContext, selection: BattleSwitchPokemonSelection,
                      width: Int, height: Int, mouseX: Int, mouseY: Int) {
        val cards = selection.tiles.map { tile ->
            val pokemon = tile.pokemon
            val status = pokemon.status?.status
            val statusLabel = status?.showdownName?.let {
                val known = if (it in setOf("brn", "par", "psn", "tox", "slp", "frz")) it else "other"
                Text.translatable("cobblemon_battle_ui.switch.status.$known")
            }
            BattlePartyCard(pokemon.species.resourceIdentifier, pokemon.aspects, pokemon.uuid,
                pokemon.getDisplayName(), pokemon.level,
                SwitchHealth.ratio(tile.showdownPokemon.condition, pokemon.maxHealth),
                tile.isFainted, tile.isCurrentlyInBattle, status, statusLabel)
        }
        val focused = selection.tiles.indexOfFirst { tile ->
            if (KeyboardTileFocus.allowsMouseHover()) tile.isHovered(mouseX.toDouble(), mouseY.toDouble())
            else KeyboardTileFocus.isFocused(tile)
        }
        draw(context, width, height, cards, focused, selection.request.forceSwitch,
            selection.isReviving, selection.opacity)
    }

    @JvmStatic
    fun draw(context: DrawContext, width: Int, height: Int, cards: List<BattlePartyCard>,
             focused: Int = -1, forceSwitch: Boolean = false, reviving: Boolean = false,
             opacity: Float = 1f) {
        val panel = BattleScreenGeometry.switchPanel(width, height)
        val tiles = BattleScreenGeometry.switchTiles(width, height, 6)
        BattleSurfaceRenderer.draw(context, panel.x(), panel.y(), panel.width(), panel.height(),
            BattleUiTheme.shell.copy(cut = 6), opacity)
        drawText(context, Text.translatable("cobblemon_battle_ui.switch.title").string,
            panel.x() + 10, panel.y() + 8, BattleUiTheme.CYAN, opacity)
        tiles.forEachIndexed { index, rect ->
            val card = cards.getOrNull(index)
            val selectable = card != null && if (reviving) card.fainted else !card.fainted && !card.active
            val border = when {
                selectable && index == focused -> BattleUiTheme.FOCUS
                card?.active == true -> BattleUiTheme.CYAN
                else -> BattleUiTheme.BORDER
            }
            BattleSurfaceRenderer.draw(context, rect.x(), rect.y(), rect.width(), rect.height(),
                BattleUiTheme.panel.copy(border = border,
                    borderWidth = if (selectable && index == focused) 2 else 1,
                    cut = 4, corners = 0b1001),
                opacity * if (card != null && !selectable) .7f else 1f)
            if (card != null) drawCard(context, rect.x(), rect.y(), card, opacity)
        }
        if (!forceSwitch) {
            drawText(context, Text.translatable("cobblemon_battle_ui.switch.back").string,
                panel.x() + 10, panel.y() + 139, BattleUiTheme.MUTED, opacity)
        }
    }

    private fun drawCard(context: DrawContext, x: Int, y: Int, card: BattlePartyCard, opacity: Float) {
        val font = MinecraftClient.getInstance().textRenderer
        val state = when {
            card.active -> Text.translatable("cobblemon_battle_ui.switch.active").string
            card.fainted -> Text.translatable("cobblemon_battle_ui.switch.fainted").string
            else -> card.statusLabel?.string ?: ""
        }
        drawText(context, "Lv.${card.level}", x + 6, y + 3, BattleUiTheme.MUTED, opacity)
        if (state.isNotEmpty()) drawText(context, font.trimToWidth(state, 68), x + 39, y + 3,
            if (card.fainted || card.statusLabel != null) BattleUiTheme.DANGER else BattleUiTheme.CYAN, opacity)
        drawText(context, font.trimToWidth(card.name.string, 80), x + 6, y + 14,
            if (card.fainted) BattleUiTheme.DIM else BattleUiTheme.TEXT, opacity)
        context.fill(x + 6, y + 29, x + 85, y + 33, BattleSurfaceRenderer.withOpacity(BattleUiTheme.TRACK, opacity))
        context.fill(x + 7, y + 30, x + 7 + (77 * card.healthRatio.coerceIn(0f, 1f)).toInt(), y + 32,
            BattleSurfaceRenderer.withOpacity(hpColor(card.healthRatio), opacity))
        BattleSurfaceRenderer.draw(context, x + 88, y + 4, 27, 27,
            BattleUiTheme.panel.copy(borderWidth = 0, cut = 3), opacity)
        PokemonModelRenderer.drawPokemonModel(context, x + 89, y + 5, 27, null,
            card.species, card.aspects, card.uuid, card.fainted, card.status, true, { it }, 1f)
    }

    private fun hpColor(hp: Float) = when {
        hp > .5f -> BattleUiTheme.GOOD
        hp > .25f -> BattleUiTheme.FOCUS
        else -> BattleUiTheme.DANGER
    }

    private fun drawText(context: DrawContext, value: String, x: Int, y: Int, color: Int, opacity: Float) {
        context.drawText(MinecraftClient.getInstance().textRenderer, value, x, y,
            BattleSurfaceRenderer.withOpacity(color, opacity), false)
    }
}

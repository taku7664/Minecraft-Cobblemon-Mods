package jbro.cobblemon.battleui.extended.ui.shared

import com.cobblemon.mod.common.api.pokemon.status.Status
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection
import com.cobblemon.mod.common.pokemon.Gender
import jbro.cobblemon.battleui.extended.TeamIndicatorUI
import jbro.cobblemon.battleui.extended.navigation.KeyboardTileFocus
import jbro.cobblemon.battleui.extended.pokemon.render.PokemonModelRenderer
import jbro.cobblemon.battleui.extended.pokemon.tooltip.TooltipDataBuilder
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
    val statusLabel: Text?,
    val details: BattlePartyDetails? = null,
    val gender: Gender? = null
)

data class BattlePartyDetails(val moves: List<Pair<String, String>>, val ability: String, val item: String)
data class BattleOpponentCard(val species: Identifier, val aspects: Set<String>, val uuid: UUID,
                              val fainted: Boolean, val status: Status?)

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
            val heldItem = pokemon.heldItem()
            val details = BattlePartyDetails(
                pokemon.moveSet.getMoves().take(4).map { it.displayName.string to "${it.currentPp}/${it.maxPp}" },
                TooltipDataBuilder.formatAbilityName(pokemon.ability.name),
                if (heldItem.isEmpty) Text.translatable("cobblemon_battle_ui.switch.none").string else heldItem.name.string
            )
            BattlePartyCard(pokemon.species.resourceIdentifier, pokemon.aspects, pokemon.uuid,
                pokemon.getDisplayName(), pokemon.level,
                SwitchHealth.ratio(tile.showdownPokemon.condition, pokemon.maxHealth),
                tile.isFainted, tile.isCurrentlyInBattle, status, statusLabel, details, pokemon.gender)
        }
        val focused = selection.tiles.indexOfFirst { tile ->
            if (KeyboardTileFocus.allowsMouseHover()) tile.isHovered(mouseX.toDouble(), mouseY.toDouble())
            else KeyboardTileFocus.isFocused(tile)
        }
        val client = MinecraftClient.getInstance()
        val battle = CobblemonClient.battle
        val playerUuid = client.player?.uuid
        val opponents = if (battle != null && playerUuid != null) {
            val playerSide = if (battle.side1.actors.any { it.uuid == playerUuid }) battle.side1 else battle.side2
            val opponentSide = if (playerSide == battle.side1) battle.side2 else battle.side1
            TeamIndicatorUI.modalTeams(playerSide, opponentSide, playerUuid, false).second
                .sortedBy { it.uuid.toString() }.mapNotNull { preview ->
                preview.speciesIdentifier?.let { BattleOpponentCard(it, preview.aspects, preview.uuid,
                    preview.isKO, preview.status) }
            }
        } else emptyList()
        draw(context, width, height, cards, focused, selection.request.forceSwitch,
            selection.isReviving, selection.opacity, opponents)
    }

    @JvmStatic
    fun draw(context: DrawContext, width: Int, height: Int, cards: List<BattlePartyCard>,
             focused: Int = -1, forceSwitch: Boolean = false, reviving: Boolean = false,
             opacity: Float = 1f, opponents: List<BattleOpponentCard> = emptyList()) {
        val panel = BattleScreenGeometry.switchPanel(width, height)
        val tiles = BattleScreenGeometry.switchTiles(width, height, cards.size)
        val highlighted = if (focused in cards.indices) focused else cards.indexOfFirst {
            if (reviving) it.fainted else !it.fainted && !it.active
        }.takeIf { it >= 0 } ?: 0
        BattleSurfaceRenderer.draw(context, panel.x(), panel.y(), panel.width(), 17,
            BattleUiTheme.shell.copy(top = 0xF81A2941.toInt(), bottom = 0xF8111D30.toInt(),
                borderWidth = 0, cornerCuts = BattleCornerCuts(topRight = 5)), opacity)
        BattleSurfaceRenderer.draw(context, panel.x() + 126, panel.y() + 18, 148, 125,
            BattleUiTheme.panel.copy(top = 0xF421354C.toInt(), bottom = 0xF0111D32.toInt(),
                borderWidth = 0, cornerCuts = BattleCornerCuts(topRight = 5, bottomRight = 10)), opacity)
        drawText(context, Text.translatable("cobblemon_battle_ui.switch.title").string,
            panel.x() + 8, panel.y() + 4, BattleUiTheme.CYAN, opacity)
        if (!forceSwitch) {
            val back = Text.translatable("cobblemon_battle_ui.switch.back").string
            drawText(context, back, panel.x() + panel.width() - 8 - MinecraftClient.getInstance().textRenderer.getWidth(back),
                panel.y() + 4, BattleUiTheme.MUTED, opacity)
        }
        tiles.forEachIndexed { index, rect ->
            val card = cards[index]
            val selectable = if (reviving) card.fainted else !card.fainted && !card.active
            val isFocused = index == highlighted
            BattleSurfaceRenderer.draw(context, rect.x(), rect.y(), rect.width(), rect.height(),
                BattleUiTheme.panel.copy(
                    top = if (isFocused) 0xFFF6D865.toInt() else if (card.active) 0xFF284D60.toInt() else 0xF9233851.toInt(),
                    bottom = if (isFocused) 0xFFD5A936.toInt() else if (card.active) 0xFF203B50.toInt() else 0xF9182B42.toInt(),
                    borderWidth = 0, cornerCuts = BattleCornerCuts(topRight = 3, bottomRight = 7)),
                opacity * if (!selectable && !isFocused) .65f else 1f)
            if (isFocused) context.fill(rect.x(), rect.y(), rect.x() + 3, rect.y() + rect.height(),
                BattleSurfaceRenderer.withOpacity(0xFFFFFFFF.toInt(), opacity))
            drawCard(context, rect.x(), rect.y(), card, isFocused, opacity)
        }
        val selected = cards.getOrNull(highlighted)
        if (selected != null) drawDetails(context, panel.x() + 130, panel.y() + 19, selected, opacity)
        if (opponents.isNotEmpty()) drawOpponents(context, width, height, opponents, opacity)
    }

    private fun drawOpponents(context: DrawContext, width: Int, height: Int,
                              opponents: List<BattleOpponentCard>, opacity: Float) {
        val font = MinecraftClient.getInstance().textRenderer
        val rows = BattleScreenGeometry.switchOpponentTiles(width, height, opponents.size.coerceAtMost(6))
        if (rows.isEmpty()) return
        val x = rows.first().x()
        val y = rows.first().y() - 17
        BattleSurfaceRenderer.draw(context, x, y, BattleScreenGeometry.SWITCH_WIDTH, 17,
            BattleUiTheme.shell.copy(top = 0xF84D3048.toInt(), bottom = 0xF8282036.toInt(),
                borderWidth = 0, cornerCuts = BattleCornerCuts(topLeft = 5)), opacity)
        drawText(context, Text.translatable("cobblemon_battle_ui.switch.opponent").string,
            x + 6, y + 4, BattleUiTheme.TRANSCRIPT_OPPONENT, opacity)
        rows.forEachIndexed { index, rect ->
            val opponent = opponents[index]
            BattleSurfaceRenderer.draw(context, rect.x(), rect.y(), rect.width(), rect.height(),
                BattleUiTheme.panel.copy(top = 0xF54A3048.toInt(), bottom = 0xF52D263E.toInt(),
                    borderWidth = 0, cornerCuts = BattleCornerCuts(topLeft = 3, bottomLeft = 7)), opacity)
            val speciesName = Text.translatable("cobblemon.species.${opponent.species.path}.name").string
            drawText(context, font.trimToWidth(speciesName, 87), rect.x() + 7, rect.y() + 5,
                if (opponent.fainted) BattleUiTheme.DIM else BattleUiTheme.TEXT, opacity)
            PokemonModelRenderer.drawPokemonModel(context, rect.x() + rect.width() - 21, rect.y() + 1, 19, null,
                opponent.species, opponent.aspects, opponent.uuid, opponent.fainted,
                opponent.status, false, { it }, 1f)
        }
    }

    private fun drawCard(context: DrawContext, x: Int, y: Int, card: BattlePartyCard, focused: Boolean, opacity: Float) {
        val font = MinecraftClient.getInstance().textRenderer
        val state = when {
            card.active -> Text.translatable("cobblemon_battle_ui.switch.active").string
            card.fainted -> Text.translatable("cobblemon_battle_ui.switch.fainted").string
            else -> card.statusLabel?.string ?: ""
        }
        val ink = if (focused) 0xFF081C2B.toInt() else if (card.fainted) BattleUiTheme.DIM else BattleUiTheme.TEXT
        BattleGenderText.draw(context, card.name.string, card.gender, x + 25, y + 2, 61, opacity, ink)
        drawText(context, "${(card.healthRatio * 100).toInt()}%", x + 89, y + 2, ink, opacity)
        val barWidth = BattleHealthBarLayout.shortWidth(67)
        context.fill(x + 25, y + 14, x + 25 + barWidth, y + 17,
            BattleSurfaceRenderer.withOpacity(BattleUiTheme.TRACK, opacity))
        context.fill(x + 25, y + 14, x + 25 + (barWidth * card.healthRatio.coerceIn(0f, 1f)).toInt(), y + 17,
            BattleSurfaceRenderer.withOpacity(hpColor(card.healthRatio), opacity))
        if (state.isNotEmpty()) drawText(context, font.trimToWidth(state, 37), x + 82, y + 11,
            if (card.statusLabel != null) BattleStatusPalette.background(card.status?.showdownName ?: "other") else ink, opacity)
        PokemonModelRenderer.drawPokemonModel(context, x + 2, y + 1, 19, null,
            card.species, card.aspects, card.uuid, card.fainted, card.status, true, { it }, 1f)
    }

    private fun drawDetails(context: DrawContext, x: Int, y: Int, card: BattlePartyCard, opacity: Float) {
        val font = MinecraftClient.getInstance().textRenderer
        val details = card.details
        BattleGenderText.draw(context, card.name.string, card.gender, x, y, 104, opacity)
        drawText(context, "Lv.${card.level}", x + 112, y, BattleUiTheme.MUTED, opacity)
        context.fill(x, y + 14, x + 140, y + 26,
            BattleSurfaceRenderer.withOpacity(0xFF174058.toInt(), opacity))
        drawText(context, Text.translatable("cobblemon_battle_ui.switch.moves").string,
            x, y + 17, BattleUiTheme.CYAN, opacity)
        details?.moves?.forEachIndexed { index, (name, pp) ->
            val rowY = y + 29 + index * 14
            if (index % 2 == 0) context.fill(x, rowY - 1, x + 140, rowY + 11,
                BattleSurfaceRenderer.withOpacity(0x7234516A, opacity))
            drawText(context, font.trimToWidth(name, 92), x + 2, rowY, BattleUiTheme.TEXT, opacity)
            drawText(context, pp, x + 100, rowY, BattleUiTheme.MUTED, opacity)
        }
        context.fill(x, y + 90, x + 140, y + 117,
            BattleSurfaceRenderer.withOpacity(0x6C0B1727, opacity))
        drawText(context, Text.translatable("cobblemon_battle_ui.switch.ability").string,
            x, y + 93, BattleUiTheme.CYAN, opacity)
        drawText(context, font.trimToWidth(details?.ability ?: "-", 92), x + 49, y + 93,
            BattleUiTheme.TEXT, opacity)
        drawText(context, Text.translatable("cobblemon_battle_ui.switch.item").string,
            x, y + 107, BattleUiTheme.CYAN, opacity)
        drawText(context, font.trimToWidth(details?.item ?: "-", 92), x + 49, y + 107,
            BattleUiTheme.TEXT, opacity)
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

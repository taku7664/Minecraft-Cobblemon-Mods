package jbro.cobblemon.battleui.extended.ui.shared

import com.cobblemon.mod.common.api.pokemon.status.Status
import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.types.ElementalType
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.gui.TypeIcon
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

data class BattlePartyMove(val name: String, val pp: String, val type: ElementalType?)
data class BattlePartyDetails(val moves: List<BattlePartyMove>, val ability: String, val item: String,
                              val types: List<ElementalType> = emptyList())
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
                pokemon.moveSet.getMoves().take(4).map {
                    BattlePartyMove(it.displayName.string, "${it.currentPp}/${it.maxPp}",
                        Moves.getByName(it.name)?.elementalType)
                },
                TooltipDataBuilder.formatAbilityName(pokemon.ability.name),
                if (heldItem.isEmpty) Text.translatable("cobblemon_battle_ui.switch.none").string else heldItem.name.string,
                listOfNotNull(pokemon.form.primaryType, pokemon.form.secondaryType)
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
        val layout = BattleSwitchLayout.calculate(width, height)
        val tiles = layout.allies.take(cards.size)
        val highlighted = if (focused in cards.indices) focused else cards.indexOfFirst {
            if (reviving) it.fainted else !it.fainted && !it.active
        }.takeIf { it >= 0 } ?: 0
        // The switch UI stays legible while the battle scene and HUD recede behind this modal.
        context.fill(0, 0, width, height,
            BattleSurfaceRenderer.withOpacity(0x8C000000.toInt(), opacity))
        drawHeader(context, layout.allyHeader, 0xF81A2941.toInt(), 0xF8111D30.toInt(), opacity)
        drawHeader(context, layout.detailHeader, 0xF8233A53.toInt(), 0xF814283E.toInt(), opacity)
        BattleSurfaceRenderer.draw(context, layout.detailBody.x, layout.detailBody.y,
            layout.detailBody.width, layout.detailBody.height,
            BattleUiTheme.panel.copy(top = 0xF421354C.toInt(), bottom = 0xF0111D32.toInt(),
                borderWidth = 0, cornerCuts = BattleCornerCuts(bottomRight = 10)), opacity)
        drawText(context, Text.translatable("cobblemon_battle_ui.switch.title").string,
            layout.allyHeader.x + 5, layout.allyHeader.y + 4, BattleUiTheme.CYAN, opacity)
        drawText(context, Text.translatable("cobblemon_battle_ui.switch.details").string,
            layout.detailHeader.x + 6, layout.detailHeader.y + 4, BattleUiTheme.TEXT, opacity)
        layout.opponentHeader?.let { header ->
            drawHeader(context, header, 0xF84D3048.toInt(), 0xF8282036.toInt(), opacity)
            drawText(context, Text.translatable("cobblemon_battle_ui.switch.opponent").string,
                header.x + 5, header.y + 4, BattleUiTheme.TRANSCRIPT_OPPONENT, opacity)
        }
        if (!forceSwitch) {
            drawText(context, "ESC",
                layout.back.x + 1, layout.back.y + 4, BattleUiTheme.MUTED, opacity)
        }
        tiles.forEachIndexed { index, rect ->
            val card = cards[index]
            val selectable = if (reviving) card.fainted else !card.fainted && !card.active
            val isFocused = index == highlighted
            BattleSurfaceRenderer.draw(context, rect.x, rect.y, rect.width, rect.height,
                BattleUiTheme.panel.copy(
                    top = if (isFocused) 0xFFCCEE67.toInt() else if (card.active) 0xFF284D60.toInt() else 0xF9233851.toInt(),
                    bottom = if (isFocused) 0xFFA8CA45.toInt() else if (card.active) 0xFF203B50.toInt() else 0xF9182B42.toInt(),
                    borderWidth = 0, cornerCuts = BattleCornerCuts(topRight = 3, bottomRight = 7)),
                opacity * if (!selectable && !isFocused) .65f else 1f)
            if (isFocused) drawFocusArrow(context, rect.x - 5, rect.y + 7, opacity)
            drawCard(context, rect.x, rect.y, card, isFocused, opacity)
        }
        val selected = cards.getOrNull(highlighted)
        if (selected != null) drawDetails(context, layout.detailBody, selected, opacity)
        if (opponents.isNotEmpty()) drawOpponents(context, layout, opponents, opacity)
    }

    private fun drawHeader(context: DrawContext, rect: jbro.cobblemon.uikit.UiRect,
                           top: Int, bottom: Int, opacity: Float) {
        BattleSurfaceRenderer.draw(context, rect.x, rect.y, rect.width, rect.height,
            BattleUiTheme.shell.copy(top = top, bottom = bottom, borderWidth = 0,
                cornerCuts = BattleCornerCuts(topRight = 5)), opacity)
    }

    private fun drawFocusArrow(context: DrawContext, x: Int, y: Int, opacity: Float) {
        val color = BattleSurfaceRenderer.withOpacity(0xFFCCEE67.toInt(), opacity)
        context.fill(x, y, x + 3, y + 6, color)
        context.fill(x + 3, y + 1, x + 4, y + 5, color)
        context.fill(x + 4, y + 2, x + 5, y + 4, color)
    }

    private fun drawOpponents(context: DrawContext, layout: BattleSwitchLayout.Result,
                              opponents: List<BattleOpponentCard>, opacity: Float) {
        val font = MinecraftClient.getInstance().textRenderer
        val rows = layout.opponents.take(opponents.size.coerceAtMost(6))
        if (rows.isEmpty()) return
        rows.forEachIndexed { index, rect ->
            val opponent = opponents[index]
            BattleSurfaceRenderer.draw(context, rect.x, rect.y, rect.width, rect.height,
                BattleUiTheme.panel.copy(top = 0xF54A3048.toInt(), bottom = 0xF52D263E.toInt(),
                    borderWidth = 0, cornerCuts = BattleCornerCuts(topLeft = 3, bottomLeft = 7)), opacity)
            val speciesName = Text.translatable("cobblemon.species.${opponent.species.path}.name").string
            drawText(context, font.trimToWidth(speciesName, rect.width - 30), rect.x + 7, rect.y + 5,
                if (opponent.fainted) BattleUiTheme.DIM else BattleUiTheme.TEXT, opacity)
            PokemonModelRenderer.drawPokemonModel(context, rect.x + rect.width - 21, rect.y + 1, 19, null,
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
        BattleGenderText.draw(context, card.name.string, card.gender, x + 23, y + 2, 55, opacity, ink)
        val health = "${(card.healthRatio * 100).toInt()}%"
        drawText(context, health, x + BattleScreenGeometry.SWITCH_WIDTH - 3 - font.getWidth(health), y + 2, ink, opacity)
        val barWidth = BattleHealthBarLayout.shortWidth(67)
        context.fill(x + 23, y + 14, x + 23 + barWidth, y + 17,
            BattleSurfaceRenderer.withOpacity(BattleUiTheme.TRACK, opacity))
        context.fill(x + 23, y + 14, x + 23 + (barWidth * card.healthRatio.coerceIn(0f, 1f)).toInt(), y + 17,
            BattleSurfaceRenderer.withOpacity(hpColor(card.healthRatio), opacity))
        if (state.isNotEmpty()) drawText(context, font.trimToWidth(state, 34), x + 69, y + 11,
            if (card.statusLabel != null) BattleStatusPalette.background(card.status?.showdownName ?: "other") else ink, opacity)
        PokemonModelRenderer.drawPokemonModel(context, x + 2, y + 1, 19, null,
            card.species, card.aspects, card.uuid, card.fainted, card.status, true, { it }, 1f)
    }

    private fun drawDetails(context: DrawContext, rect: jbro.cobblemon.uikit.UiRect,
                            card: BattlePartyCard, opacity: Float) {
        val font = MinecraftClient.getInstance().textRenderer
        val details = card.details
        val x = rect.x + 6
        val y = rect.y + 4
        val innerWidth = rect.width - 12
        val level = "Lv.${card.level}"
        BattleSurfaceRenderer.draw(context, x, y, 36, 36,
            BattleUiTheme.panel.copy(top = 0xFF29435D.toInt(), bottom = 0xFF13283F.toInt(),
                borderWidth = 0, cornerCuts = BattleCornerCuts(topLeft = 3, bottomRight = 6)), opacity)
        PokemonModelRenderer.drawPokemonModel(context, x - 4, y, 44, null,
            card.species, card.aspects, card.uuid, card.fainted, card.status, true, { it }, 1f)
        BattleGenderText.draw(context, card.name.string, card.gender, x + 42, y + 4,
            innerWidth - font.getWidth(level) - 50, opacity)
        drawText(context, level, x + innerWidth - font.getWidth(level), y + 4, BattleUiTheme.MUTED, opacity)
        var typeX = x + 42
        details?.types?.take(2)?.forEach { type ->
            TypeIcon(x = typeX.toFloat(), y = (y + 18).toFloat(),
                type = type, small = true, opacity = opacity).render(context)
            val label = type.displayName.string
            drawText(context, label, typeX + 12, y + 18, BattleUiTheme.MUTED, opacity)
            typeX += 20 + font.getWidth(label)
        }
        context.fill(x, y + 36, x + innerWidth, y + 48,
            BattleSurfaceRenderer.withOpacity(0xFF174058.toInt(), opacity))
        drawText(context, Text.translatable("cobblemon_battle_ui.switch.moves").string,
            x + 4, y + 38, BattleUiTheme.CYAN, opacity)
        details?.moves?.forEachIndexed { index, move ->
            val rowY = y + 50 + index * 12
            if (index % 2 == 0) context.fill(x, rowY - 1, x + innerWidth, rowY + 10,
                BattleSurfaceRenderer.withOpacity(0x7234516A, opacity))
            move.type?.let { TypeIcon(x = (x + 8).toFloat(), y = rowY.toFloat(),
                type = it, small = true, opacity = opacity).render(context) }
            val ppX = x + innerWidth - 4 - font.getWidth(move.pp)
            drawText(context, font.trimToWidth(move.name, (ppX - x - 23).coerceAtLeast(0)),
                x + 19, rowY, BattleUiTheme.TEXT, opacity)
            drawText(context, move.pp, ppX, rowY, BattleUiTheme.MUTED, opacity)
        }
        context.fill(x, y + 98, x + innerWidth, y + 120,
            BattleSurfaceRenderer.withOpacity(0x6C0B1727, opacity))
        drawText(context, Text.translatable("cobblemon_battle_ui.switch.ability").string,
            x + 3, y + 100, BattleUiTheme.CYAN, opacity)
        drawText(context, font.trimToWidth(details?.ability ?: "-", innerWidth - 53), x + 50, y + 100,
            BattleUiTheme.TEXT, opacity)
        drawText(context, Text.translatable("cobblemon_battle_ui.switch.item").string,
            x + 3, y + 111, BattleUiTheme.CYAN, opacity)
        drawText(context, font.trimToWidth(details?.item ?: "-", innerWidth - 53), x + 50, y + 111,
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

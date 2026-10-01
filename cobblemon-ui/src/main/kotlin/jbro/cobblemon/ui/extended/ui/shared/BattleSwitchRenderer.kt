package jbro.cobblemon.ui.extended.ui.shared

import com.cobblemon.mod.common.api.pokemon.status.Status
import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.types.ElementalType
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.gui.TypeIcon
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection
import com.cobblemon.mod.common.pokemon.Gender
import com.mojang.blaze3d.systems.RenderSystem
import jbro.cobblemon.ui.extended.TeamIndicatorUI
import jbro.cobblemon.ui.extended.navigation.KeyboardTileFocus
import jbro.cobblemon.ui.extended.pokemon.render.PokemonModelRenderer
import jbro.cobblemon.ui.extended.pokemon.tooltip.TooltipDataBuilder
import jbro.cobblemon.ui.navigation.BattleScreenGeometry
import jbro.cobblemon.ui.navigation.SwitchHealth
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.world.item.ItemStack
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import java.util.UUID

data class BattlePartyCard(
    val species: ResourceLocation,
    val aspects: Set<String>,
    val uuid: UUID,
    val name: Component,
    val level: Int,
    val healthRatio: Float,
    val fainted: Boolean,
    val active: Boolean,
    val status: Status?,
    val statusLabel: Component?,
    val details: BattlePartyDetails? = null,
    val gender: Gender? = null,
    val heldItem: ItemStack = ItemStack.EMPTY
)

data class BattlePartyMove(val name: String, val pp: String, val type: ElementalType?)
data class BattlePartyDetails(val moves: List<BattlePartyMove>, val ability: String, val item: String,
                              val types: List<ElementalType> = emptyList())
data class BattleOpponentCard(val species: ResourceLocation, val aspects: Set<String>, val uuid: UUID,
                              val fainted: Boolean, val status: Status?)

/** The party preview and the live Cobblemon switch selection share this visual path. */
object BattleSwitchRenderer {
    private const val FOCUS_FILL = 0xFFCCEE67.toInt()
    private val SWITCH_CURSOR = Any()

    @JvmStatic
    fun drawSelection(context: GuiGraphics, selection: BattleSwitchPokemonSelection,
                      width: Int, height: Int, mouseX: Int, mouseY: Int) {
        val cards = selection.tiles.map { tile ->
            val pokemon = tile.pokemon
            val status = pokemon.status?.status
            val statusLabel = status?.showdownName?.let {
                val known = if (it in setOf("brn", "par", "psn", "tox", "slp", "frz")) it else "other"
                Component.translatable("cobblemon_ui.switch.status.$known")
            }
            val heldItem = pokemon.heldItem()
            val details = BattlePartyDetails(
                pokemon.moveSet.getMoves().take(4).map {
                    BattlePartyMove(it.displayName.string, "${it.currentPp}/${it.maxPp}",
                        Moves.getByName(it.name)?.elementalType)
                },
                TooltipDataBuilder.formatAbilityName(pokemon.ability.name),
                if (heldItem.isEmpty) Component.translatable("cobblemon_ui.switch.none").string else heldItem.hoverName.string,
                listOfNotNull(pokemon.form.primaryType, pokemon.form.secondaryType)
            )
            BattlePartyCard(pokemon.species.resourceIdentifier, pokemon.aspects, pokemon.uuid,
                pokemon.getDisplayName(), pokemon.level,
                SwitchHealth.ratio(tile.showdownPokemon.condition, pokemon.maxHealth),
                tile.isFainted, tile.isCurrentlyInBattle, status, statusLabel, details, pokemon.gender,
                heldItem.copy())
        }
        val focused = selection.tiles.indexOfFirst { tile ->
            if (KeyboardTileFocus.allowsMouseHover()) tile.isHovered(mouseX.toDouble(), mouseY.toDouble())
            else KeyboardTileFocus.isFocused(tile)
        }
        val client = Minecraft.getInstance()
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
    fun draw(context: GuiGraphics, width: Int, height: Int, cards: List<BattlePartyCard>,
             focused: Int = -1, forceSwitch: Boolean = false, reviving: Boolean = false,
             opacity: Float = 1f, opponents: List<BattleOpponentCard> = emptyList()) {
        val layout = BattleSwitchLayout.calculate(width, height)
        val tiles = layout.allies.take(cards.size)
        val highlighted = if (focused in cards.indices) focused else cards.indexOfFirst {
            if (reviving) it.fainted else !it.fainted && !it.active
        }.takeIf { it >= 0 } ?: 0
        // The switch UI stays legible while the battle scene and HUD recede behind this modal.
        context.fill(0, 0, width, height,
            BattleSurfaceRenderer.withOpacity(BattleUiTheme.MODAL_SCRIM, opacity))
        BattleSurfaceRenderer.draw(context, layout.panel.x - 3, layout.panel.y - 3,
            layout.panel.width + 6, layout.detailBody.bottom - layout.panel.y + 6,
            BattleUiTheme.modalBackdrop.copy(cornerCuts = BattleCornerCuts(8, 8, 8, 8)), opacity)
        drawHeader(context, layout.allyHeader, 0xF81A2941.toInt(), 0xF8111D30.toInt(), opacity)
        drawHeader(context, layout.detailHeader, 0xF8233A53.toInt(), 0xF814283E.toInt(), opacity)
        BattleSurfaceRenderer.draw(context, layout.detailBody.x, layout.detailBody.y,
            layout.detailBody.width, layout.detailBody.height,
            BattleUiTheme.panel.copy(top = 0xF421354C.toInt(), bottom = 0xF0111D32.toInt(),
                borderWidth = 0, cornerCuts = BattleCornerCuts(bottomRight = 6, bottomLeft = 6)), opacity)
        drawText(context, Component.translatable("cobblemon_ui.switch.title").string,
            layout.allyHeader.x + 5, layout.allyHeader.y + 4, BattleUiTheme.CYAN, opacity)
        drawText(context, Component.translatable("cobblemon_ui.switch.details").string,
            layout.detailHeader.x + 6, layout.detailHeader.y + 4, BattleUiTheme.TEXT, opacity)
        layout.opponentHeader?.let { header ->
            drawHeader(context, header, 0xF84D3048.toInt(), 0xF8282036.toInt(), opacity)
            drawText(context, Component.translatable("cobblemon_ui.switch.opponent").string,
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
            val emphasis = BattleFocusMotion.emphasis(card.uuid to "switch", isFocused)
            val corners = BattleCornerCuts(5, 5, 5, 5)
            val restTop = if (card.active) 0xFF284D60.toInt() else 0xF9233851.toInt()
            val restBottom = if (card.active) 0xFF203B50.toInt() else 0xF9182B42.toInt()
            BattleControlRenderer.drawFocusHalo(context, rect.x, rect.y, rect.width, rect.height, corners,
                FOCUS_FILL, emphasis, opacity)
            BattleSurfaceRenderer.draw(context, rect.x, rect.y, rect.width, rect.height,
                BattleUiTheme.panel.copy(
                    top = BattleSurfaceRenderer.interpolate(restTop, FOCUS_FILL, emphasis),
                    bottom = BattleSurfaceRenderer.interpolate(restBottom, 0xFFA8CA45.toInt(), emphasis),
                    borderWidth = 0, cornerCuts = corners),
                opacity * if (!selectable && !isFocused) .65f else 1f)
            drawCard(context, rect.x, rect.y, card, emphasis > .5f, opacity)
        }
        tiles.getOrNull(highlighted)?.let { rect ->
            BattleControlRenderer.drawCursor(context, SWITCH_CURSOR, rect.x - 2, rect.y + rect.height / 2f,
                FOCUS_FILL, opacity)
        }
        val selected = cards.getOrNull(highlighted)
        if (selected != null) drawDetails(context, layout.detailBody, selected, opacity)
        if (opponents.isNotEmpty()) drawOpponents(context, layout, opponents, opacity)
    }

    private fun drawHeader(context: GuiGraphics, rect: jbro.cobblemon.uikit.UiRect,
                           top: Int, bottom: Int, opacity: Float) {
        BattleSurfaceRenderer.draw(context, rect.x, rect.y, rect.width, rect.height,
            BattleUiTheme.shell.copy(top = top, bottom = bottom, borderWidth = 0,
                cornerCuts = BattleCornerCuts(topLeft = 5, topRight = 5)), opacity)
    }

    private fun drawOpponents(context: GuiGraphics, layout: BattleSwitchLayout.Result,
                              opponents: List<BattleOpponentCard>, opacity: Float) {
        val font = Minecraft.getInstance().font
        val rows = layout.opponents.take(opponents.size.coerceAtMost(6))
        if (rows.isEmpty()) return
        rows.forEachIndexed { index, rect ->
            val opponent = opponents[index]
            BattleSurfaceRenderer.draw(context, rect.x, rect.y, rect.width, rect.height,
                BattleUiTheme.panel.copy(top = 0xF54A3048.toInt(), bottom = 0xF52D263E.toInt(),
                    borderWidth = 0, cornerCuts = BattleCornerCuts(5, 5, 5, 5)), opacity)
            val speciesName = Component.translatable("cobblemon.species.${opponent.species.path}.name").string
            drawText(context, font.plainSubstrByWidth(speciesName, rect.width - 30), rect.x + 7, rect.y + 5,
                if (opponent.fainted) BattleUiTheme.DIM else BattleUiTheme.TEXT, opacity)
            PokemonModelRenderer.drawPokemonModel(context, rect.x + rect.width - 21, rect.y + 1, 19, null,
                opponent.species, opponent.aspects, opponent.uuid, opponent.fainted,
                opponent.status, false, { it }, 1f)
        }
    }

    private fun drawCard(context: GuiGraphics, x: Int, y: Int, card: BattlePartyCard, focused: Boolean, opacity: Float) {
        val font = Minecraft.getInstance().font
        val state = when {
            card.active -> Component.translatable("cobblemon_ui.switch.active").string
            card.fainted -> Component.translatable("cobblemon_ui.switch.fainted").string
            else -> card.statusLabel?.string ?: ""
        }
        val ink = if (focused) 0xFF081C2B.toInt() else if (card.fainted) BattleUiTheme.DIM else BattleUiTheme.TEXT
        BattleGenderText.draw(context, card.name.string, card.gender, x + 23, y + 2, 55, opacity, ink)
        val health = "${(card.healthRatio * 100).toInt()}%"
        drawText(context, health, x + BattleScreenGeometry.SWITCH_WIDTH - 3 - font.width(health), y + 2, ink, opacity)
        val barWidth = BattleHealthBarLayout.shortWidth(67)
        BattleSurfaceRenderer.gauge(context, x + 23, y + 14, barWidth, 4, card.healthRatio,
            BattleUiTheme.TRACK, hpColor(card.healthRatio), opacity, inset = 0)
        if (state.isNotEmpty()) drawText(context, font.plainSubstrByWidth(state, 34), x + 69, y + 11,
            if (card.statusLabel != null) BattleStatusPalette.background(card.status?.showdownName ?: "other") else ink, opacity)
        PokemonModelRenderer.drawPokemonModel(context, x + 2, y + 1, 19, null,
            card.species, card.aspects, card.uuid, card.fainted, card.status, true, { it }, 1f)
        drawHeldItemIcon(context, card.heldItem, x + 14, y + 11, 8, opacity)
    }

    private fun drawDetails(context: GuiGraphics, rect: jbro.cobblemon.uikit.UiRect,
                            card: BattlePartyCard, opacity: Float) {
        val font = Minecraft.getInstance().font
        val details = card.details
        val x = rect.x + 6
        val y = rect.y + 4
        val innerWidth = rect.width - 12
        val level = "Lv.${card.level}"
        BattleSurfaceRenderer.draw(context, x, y, 36, 36,
            BattleUiTheme.panel.copy(top = 0xFF29435D.toInt(), bottom = 0xFF13283F.toInt(),
                borderWidth = 0, cornerCuts = BattleCornerCuts(6, 6, 6, 6)), opacity)
        PokemonModelRenderer.drawPokemonModel(context, x - 4, y, 44, null,
            card.species, card.aspects, card.uuid, card.fainted, card.status, true, { it }, 1f)
        drawHeldItemIcon(context, card.heldItem, x + 26, y + 26, 10, opacity)
        BattleGenderText.draw(context, card.name.string, card.gender, x + 42, y + 4,
            innerWidth - font.width(level) - 50, opacity)
        drawText(context, level, x + innerWidth - font.width(level), y + 4, BattleUiTheme.MUTED, opacity)
        var typeX = x + 42
        details?.types?.take(2)?.forEach { type ->
            TypeIcon(x = typeX.toFloat(), y = (y + 18).toFloat(),
                type = type, small = true, opacity = opacity).render(context)
            val label = type.displayName.string
            drawText(context, label, typeX + 12, y + 18, BattleUiTheme.MUTED, opacity)
            typeX += 20 + font.width(label)
        }
        BattleSurfaceRenderer.draw(context, x, y + 36, innerWidth, 12,
            BattleSurface(0xFF174058.toInt(), cut = 4), opacity)
        drawText(context, Component.translatable("cobblemon_ui.switch.moves").string,
            x + 4, y + 38, BattleUiTheme.CYAN, opacity)
        details?.moves?.forEachIndexed { index, move ->
            val rowY = y + 50 + index * 12
            if (index % 2 == 0) BattleSurfaceRenderer.draw(context, x, rowY - 1, innerWidth, 11,
                BattleSurface(0x7234516A, cut = 3), opacity)
            move.type?.let { TypeIcon(x = (x + 8).toFloat(), y = rowY.toFloat(),
                type = it, small = true, opacity = opacity).render(context) }
            val ppX = x + innerWidth - 4 - font.width(move.pp)
            drawText(context, font.plainSubstrByWidth(move.name, (ppX - x - 23).coerceAtLeast(0)),
                x + 19, rowY, BattleUiTheme.TEXT, opacity)
            drawText(context, move.pp, ppX, rowY, BattleUiTheme.MUTED, opacity)
        }
        BattleSurfaceRenderer.draw(context, x, y + 98, innerWidth, 22,
            BattleSurface(0x6C0B1727, cut = 5), opacity)
        drawText(context, Component.translatable("cobblemon_ui.switch.ability").string,
            x + 3, y + 100, BattleUiTheme.CYAN, opacity)
        drawText(context, font.plainSubstrByWidth(details?.ability ?: "-", innerWidth - 53), x + 50, y + 100,
            BattleUiTheme.TEXT, opacity)
        drawText(context, Component.translatable("cobblemon_ui.switch.item").string,
            x + 3, y + 111, BattleUiTheme.CYAN, opacity)
        drawText(context, font.plainSubstrByWidth(details?.item ?: "-", innerWidth - 53), x + 50, y + 111,
            BattleUiTheme.TEXT, opacity)
    }

    private fun hpColor(hp: Float) = when {
        hp > .5f -> BattleUiTheme.GOOD
        hp > .25f -> BattleUiTheme.FOCUS
        else -> BattleUiTheme.DANGER
    }

    private fun drawHeldItemIcon(context: GuiGraphics, item: ItemStack, x: Int, y: Int,
                                 size: Int, opacity: Float) {
        if (item.isEmpty || opacity <= 0f) return
        context.flush()
        context.pose().pushPose()
        try {
            context.pose().translate(x.toDouble(), y.toDouble(), 0.0)
            val scale = size / 16f
            context.pose().scale(scale, scale, 1f)
            RenderSystem.enableBlend()
            RenderSystem.defaultBlendFunc()
            RenderSystem.setShaderColor(1f, 1f, 1f, opacity.coerceIn(0f, 1f))
            context.renderItem(item, 0, 0)
            context.flush()
        } finally {
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
            context.pose().popPose()
        }
    }

    private fun drawText(context: GuiGraphics, value: String, x: Int, y: Int, color: Int, opacity: Float) {
        context.drawString(Minecraft.getInstance().font, value, x, y,
            BattleSurfaceRenderer.withOpacity(color, opacity), false)
    }
}

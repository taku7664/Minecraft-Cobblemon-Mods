package jbro.cobblemon.battleui.extended.ui.shared

import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleTargetSelection
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection
import com.cobblemon.mod.common.api.pokemon.status.Status
import jbro.cobblemon.battleui.extended.navigation.KeyboardTileFocus
import jbro.cobblemon.battleui.extended.pokemon.render.PokemonModelRenderer
import jbro.cobblemon.battleui.navigation.BattleScreenGeometry
import jbro.cobblemon.battleui.navigation.UiRect
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import java.util.UUID

internal data class BattleTargetCard(
    val species: Identifier,
    val aspects: Set<String>,
    val uuid: UUID,
    val name: Text,
    val ally: Boolean,
    val selectable: Boolean,
    val fainted: Boolean,
    val focused: Boolean,
    val status: Status? = null
)

/** Shared target composition for the functionless draft and Cobblemon's live selection. */
object BattleTargetRenderer {
    private val TARGET_CURSOR = Any()

    @JvmStatic
    fun supports(selection: BattleTargetSelection): Boolean {
        val slots = selection.request.activePokemon.getSidePokemon().count()
        return slots in 2..3 && selection.targetTiles.size == slots * 2 &&
            selection.targetTiles.all { it.target.battlePokemon != null }
    }

    @JvmStatic
    fun bounds(selection: BattleTargetSelection, width: Int, height: Int): List<UiRect> {
        if (!supports(selection)) return emptyList()
        val active = selection.request.activePokemon
        val slots = active.getSidePokemon().count()
        return selection.targetTiles.mapIndexed { index, tile ->
            val slot = BattleScreenGeometry.targetTileForIndex(width, height, slots, index,
                tile.target.isAllied(active))
            BattleScreenGeometry.targetCard(slot)
        }
    }

    @JvmStatic
    fun drawSelection(context: DrawContext, selection: BattleTargetSelection,
                      width: Int, height: Int, mouseX: Int, mouseY: Int) {
        val opacity = selection.opacity
        if (opacity <= .05f) return
        val slots = selection.request.activePokemon.getSidePokemon().count()
        drawChrome(context, width, height, slots, opacity)
        val cards = bounds(selection, width, height)
        selection.targetTiles.forEachIndexed { index, tile ->
            val pokemon = tile.target.battlePokemon ?: return@forEachIndexed
            val focused = tile.selectable && if (KeyboardTileFocus.allowsMouseHover()) {
                cards[index].contains(mouseX.toDouble(), mouseY.toDouble())
            } else KeyboardTileFocus.isFocused(tile)
            drawCard(context, cards[index], BattleTargetCard(
                pokemon.species.resourceIdentifier, pokemon.state.currentAspects, pokemon.uuid,
                pokemon.displayName, tile.target.isAllied(selection.request.activePokemon),
                tile.selectable, pokemon.hpValue <= 0f, focused, pokemon.status
            ), opacity)
        }
    }

    @JvmStatic
    fun click(selection: BattleTargetSelection, mouseX: Double, mouseY: Double,
              width: Int, height: Int): Boolean {
        if (!supports(selection)) return false
        val slots = selection.request.activePokemon.getSidePokemon().count()
        if (BattleScreenGeometry.targetBack(width, height, slots).contains(mouseX, mouseY)) {
            selection.playDownSound(MinecraftClient.getInstance().soundManager)
            selection.battleGUI.changeActionSelection(BattleMoveSelection(selection.battleGUI, selection.request))
            return true
        }
        val index = bounds(selection, width, height).indexOfFirst { it.contains(mouseX, mouseY) }
        if (index < 0) return false
        selection.targetTiles[index].onClick() // Cobblemon owns eligibility, gimmick use and response submission.
        return true
    }

    internal fun drawChrome(context: DrawContext, width: Int, height: Int, slots: Int,
                            opacity: Float = 1f) {
        val panel = BattleScreenGeometry.targetPanel(width, height, slots)
        context.fill(0, 0, width, height,
            BattleSurfaceRenderer.withOpacity(BattleUiTheme.MODAL_SCRIM, opacity))
        BattleSurfaceRenderer.draw(context, panel.x() - 3, panel.y() - 3,
            panel.width() + 6, panel.height() + 6, BattleUiTheme.modalBackdrop, opacity)
        BattleSurfaceRenderer.draw(context, panel.x() + 3, panel.y() + 3,
            panel.width() - 6, 16,
            BattleUiTheme.panel.copy(top = 0xF8233A53.toInt(), bottom = 0xF814283E.toInt(),
                cornerCuts = BattleCornerCuts(topLeft = 5, topRight = 5)), opacity)
        drawText(context, Text.translatable("cobblemon_battle_ui.target.title").string,
            panel.x() + 10, panel.y() + 6, BattleUiTheme.TEXT, opacity)
        drawText(context, Text.translatable("cobblemon_battle_ui.target.opponent").string,
            panel.x() + 8, panel.y() + 20, BattleUiTheme.PURPLE, opacity)
        drawText(context, Text.translatable("cobblemon_battle_ui.target.ally").string,
            panel.x() + 8, panel.y() + 51, BattleUiTheme.CYAN, opacity)
        val back = BattleScreenGeometry.targetBack(width, height, slots)
        val label = Text.translatable("cobblemon_battle_ui.target.back").string
        val arrow = BattleSurfaceRenderer.withOpacity(BattleUiTheme.CYAN, opacity)
        context.fill(back.x() + 3, back.y() + 5, back.x() + 8, back.y() + 6, arrow)
        context.fill(back.x() + 2, back.y() + 4, back.x() + 4, back.y() + 7, arrow)
        drawText(context, label, back.x() + 10, back.y() + 1, BattleUiTheme.TEXT, opacity)
    }

    internal fun drawCard(context: DrawContext, rect: UiRect, card: BattleTargetCard,
                          opacity: Float = 1f) {
        val x = rect.x()
        val y = rect.y()
        val portraitSize = 18
        val cardOpacity = opacity * if (card.selectable) 1f else .7f
        val focused = card.focused && card.selectable
        val emphasis = BattleFocusMotion.emphasis(card.uuid to "target", focused)
        val corners = BattleCornerCuts(5, 5, 5, 5)
        BattleControlRenderer.drawFocusHalo(context, x, y, rect.width(), rect.height(), corners,
            0xFFCCEE67.toInt(), emphasis, opacity)
        BattleSurfaceRenderer.draw(context, x, y, rect.width(), rect.height(),
            BattleUiTheme.panel.copy(
                top = BattleSurfaceRenderer.interpolate(if (card.ally) 0xF9233851.toInt() else 0xF84D3048.toInt(),
                    0xFFCCEE67.toInt(), emphasis),
                bottom = BattleSurfaceRenderer.interpolate(if (card.ally) 0xF9182B42.toInt() else 0xF8282036.toInt(),
                    0xFFA8CA45.toInt(), emphasis),
                borderWidth = 0, cornerCuts = corners), cardOpacity)
        if (focused) BattleControlRenderer.drawCursor(context, TARGET_CURSOR, x - 2, y + rect.height() / 2f,
            0xFFCCEE67.toInt(), opacity)
        val font = MinecraftClient.getInstance().textRenderer
        val textX = x + if (card.selectable) 7 else 13
        val portraitX = x + rect.width() - portraitSize - 3
        val name = font.trimToWidth(card.name.string, (portraitX - textX - 3).coerceAtLeast(0))
        val textY = y + (rect.height() - font.fontHeight) / 2
        drawText(context, name,
            textX, textY, if (focused) 0xFF102235.toInt() else if (card.selectable) BattleUiTheme.TEXT else BattleUiTheme.MUTED,
            cardOpacity)
        if (!card.selectable) drawText(context, "×", x + 3, textY, BattleUiTheme.DANGER, cardOpacity)
        val portraitY = y + (rect.height() - portraitSize) / 2
        PokemonModelRenderer.drawPokemonModel(context, portraitX, portraitY, portraitSize,
            null, card.species, card.aspects, card.uuid, card.fainted, card.status, card.ally,
            { BattleSurfaceRenderer.withOpacity(it, cardOpacity) }, 1f)
    }

    private fun drawText(context: DrawContext, value: String, x: Int, y: Int,
                         color: Int, opacity: Float) {
        context.drawText(MinecraftClient.getInstance().textRenderer, value, x, y,
            BattleSurfaceRenderer.withOpacity(color, opacity), false)
    }

}

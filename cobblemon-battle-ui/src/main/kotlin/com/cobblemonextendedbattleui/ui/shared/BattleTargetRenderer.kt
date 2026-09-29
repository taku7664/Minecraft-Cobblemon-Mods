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
        BattleSurfaceRenderer.draw(context, panel.x() + 6, panel.y() + 4,
            panel.width() - 12, 18,
            BattleUiTheme.panel.copy(top = 0xF8233A53.toInt(), bottom = 0xF814283E.toInt(),
                cornerCuts = BattleCornerCuts(topLeft = 3, topRight = 3)), opacity)
        val font = MinecraftClient.getInstance().textRenderer
        drawText(context, Text.translatable("cobblemon_battle_ui.target.title").string,
            panel.x() + 13, panel.y() + 8, BattleUiTheme.TEXT, opacity)
        drawText(context, Text.translatable("cobblemon_battle_ui.target.opponent").string,
            panel.x() + 10, panel.y() + 25, BattleUiTheme.PURPLE, opacity)
        drawText(context, Text.translatable("cobblemon_battle_ui.target.ally").string,
            panel.x() + 10, panel.y() + 59, BattleUiTheme.CYAN, opacity)
        val back = BattleScreenGeometry.targetBack(width, height, slots)
        BattleSurfaceRenderer.draw(context, back.x(), back.y(), back.width(), back.height(),
            BattleUiTheme.secondary.copy(cut = 3, corners = 0b1010), opacity)
        val label = Text.translatable("cobblemon_battle_ui.target.back").string
        drawText(context, label, back.x() + (back.width() - font.getWidth(label)) / 2,
            back.y() + (back.height() - font.fontHeight) / 2, BattleUiTheme.TEXT, opacity)
    }

    internal fun drawCard(context: DrawContext, rect: UiRect, card: BattleTargetCard,
                          opacity: Float = 1f) {
        val x = rect.x()
        val y = rect.y()
        val portraitSize = 16
        val cardOpacity = opacity * if (card.selectable) 1f else .7f
        val focused = card.focused && card.selectable
        BattleSurfaceRenderer.draw(context, x, y, rect.width(), rect.height(),
            BattleUiTheme.panel.copy(
                top = if (focused) 0xFFCCEE67.toInt() else if (card.ally) 0xF9233851.toInt() else 0xF84D3048.toInt(),
                bottom = if (focused) 0xFFA8CA45.toInt() else if (card.ally) 0xF9182B42.toInt() else 0xF8282036.toInt(),
                borderWidth = 0, cornerCuts = BattleCornerCuts(topRight = 3, bottomRight = 7)), cardOpacity)
        val font = MinecraftClient.getInstance().textRenderer
        val name = font.trimToWidth(card.name.string,
            (rect.width() - portraitSize - if (card.selectable) 15 else 23).coerceAtLeast(0))
        val groupWidth = font.getWidth(name) + 5 + portraitSize
        val textX = x + maxOf((rect.width() - groupWidth) / 2, if (card.selectable) 0 else 12)
        val portraitX = textX + font.getWidth(name) + 5
        val textY = y + (rect.height() - font.fontHeight) / 2
        drawText(context, name,
            textX, textY, if (focused) 0xFF102235.toInt() else if (card.selectable) BattleUiTheme.TEXT else BattleUiTheme.MUTED,
            cardOpacity)
        if (!card.selectable) drawText(context, "×", x + 3, textY, BattleUiTheme.DANGER, cardOpacity)
        val portraitY = y + (rect.height() - portraitSize) / 2
        BattleSurfaceRenderer.draw(context, portraitX, portraitY, portraitSize, portraitSize,
            BattleUiTheme.panel.copy(cut = 3), cardOpacity)
        PokemonModelRenderer.drawPokemonModel(context, portraitX + 1, portraitY + 1, portraitSize - 2,
            null, card.species, card.aspects, card.uuid, card.fainted, card.status, card.ally,
            { BattleSurfaceRenderer.withOpacity(it, cardOpacity) }, 1f)
    }

    private fun drawText(context: DrawContext, value: String, x: Int, y: Int,
                         color: Int, opacity: Float) {
        context.drawText(MinecraftClient.getInstance().textRenderer, value, x, y,
            BattleSurfaceRenderer.withOpacity(color, opacity), false)
    }

}

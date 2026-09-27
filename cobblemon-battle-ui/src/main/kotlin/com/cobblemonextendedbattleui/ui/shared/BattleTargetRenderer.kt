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
            BattleScreenGeometry.targetTileForIndex(width, height, slots, index, tile.target.isAllied(active))
        }
    }

    @JvmStatic
    fun drawSelection(context: DrawContext, selection: BattleTargetSelection,
                      width: Int, height: Int, mouseX: Int, mouseY: Int) {
        val opacity = selection.opacity
        if (opacity <= .05f) return
        val slots = selection.request.activePokemon.getSidePokemon().count()
        drawChrome(context, width, height, slots, opacity, true)
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
                            opacity: Float = 1f, dimBackground: Boolean = false) {
        if (dimBackground) context.fill(0, 0, width, height,
            BattleSurfaceRenderer.withOpacity(0x4906101E, opacity))
        val panel = BattleScreenGeometry.targetPanel(width, height, slots)
        drawLabel(context, Text.translatable("cobblemon_battle_ui.target.title").string,
            panel.x() + 6, panel.y() + 2, opacity)
        drawLabel(context, Text.translatable("cobblemon_battle_ui.target.ally").string,
            panel.x() + 6, panel.y() + 12, opacity)
        drawLabel(context, Text.translatable("cobblemon_battle_ui.target.opponent").string,
            BattleScreenGeometry.targetTile(width, height, slots, 1, 0).x(), panel.y() + 12, opacity)
        val back = BattleScreenGeometry.targetBack(width, height, slots)
        BattleSurfaceRenderer.draw(context, back.x(), back.y(), back.width(), back.height(),
            BattleUiTheme.secondary.copy(border = BattleUiTheme.CYAN, cut = 3, corners = 0b1010), opacity)
        val label = Text.translatable("cobblemon_battle_ui.target.back").string
        val font = MinecraftClient.getInstance().textRenderer
        drawText(context, label, back.x() + (back.width() - font.getWidth(label)) / 2,
            back.y() + 2, BattleUiTheme.TEXT, opacity)
    }

    internal fun drawCard(context: DrawContext, rect: UiRect, card: BattleTargetCard,
                          opacity: Float = 1f) {
        val x = rect.x()
        val y = rect.y()
        val portraitSize = 16
        val portraitX = x + rect.width() - portraitSize - 4
        val accent = when {
            card.focused && card.selectable -> BattleUiTheme.FOCUS
            card.ally -> BattleUiTheme.CYAN
            else -> BattleUiTheme.PURPLE
        }
        val cardOpacity = opacity * if (card.selectable) 1f else .7f
        BattleSurfaceRenderer.draw(context, x, y, rect.width(), rect.height(),
            BattleUiTheme.panel.copy(border = accent,
                borderWidth = if (card.focused && card.selectable) 2 else 1,
                cut = 3, corners = 0b1001), cardOpacity)
        val font = MinecraftClient.getInstance().textRenderer
        val textX = x + if (card.selectable) 5 else 15
        drawText(context, font.trimToWidth(card.name.string, portraitX - textX - 5),
            textX, y + 7, if (card.selectable) BattleUiTheme.TEXT else BattleUiTheme.MUTED, cardOpacity)
        if (!card.selectable) drawText(context, "×", x + 5, y + 7, BattleUiTheme.DANGER, cardOpacity)
        BattleSurfaceRenderer.draw(context, portraitX, y + 3, portraitSize, portraitSize,
            BattleUiTheme.panel.copy(border = accent, cut = 3), cardOpacity)
        PokemonModelRenderer.drawPokemonModel(context, portraitX + 1, y + 4, portraitSize - 2,
            null, card.species, card.aspects, card.uuid, card.fainted, card.status, card.ally,
            { BattleSurfaceRenderer.withOpacity(it, cardOpacity) }, 1f)
    }

    private fun drawText(context: DrawContext, value: String, x: Int, y: Int,
                         color: Int, opacity: Float) {
        context.drawText(MinecraftClient.getInstance().textRenderer, value, x, y,
            BattleSurfaceRenderer.withOpacity(color, opacity), false)
    }

    private fun drawLabel(context: DrawContext, value: String, x: Int, y: Int, opacity: Float) {
        val font = MinecraftClient.getInstance().textRenderer
        BattleSurfaceRenderer.draw(context, x - 2, y - 1, font.getWidth(value) + 4, 11,
            BattleUiTheme.row.copy(top = 0xD9101A2D.toInt(), bottom = 0xD9101A2D.toInt(),
                cut = 2, corners = 0b1010), opacity)
        drawText(context, value, x, y, BattleUiTheme.TEXT, opacity)
    }
}

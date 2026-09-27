package jbro.cobblemon.battleui.extended.ui.shared

import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.api.moves.MoveTemplate
import com.cobblemon.mod.common.api.types.ElementalType
import com.cobblemon.mod.common.client.gui.TypeIcon
import com.cobblemon.mod.common.client.gui.MoveCategoryIcon
import com.cobblemon.mod.common.client.gui.battle.BattleGUI
import com.cobblemon.mod.common.client.gui.battle.widgets.BattleOptionTile
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection
import jbro.cobblemon.battleui.extended.UIUtils
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text

object BattleControlRenderer {
    @JvmStatic
    fun option(context: DrawContext, tile: BattleOptionTile, focused: Boolean) {
        val opacity = CobblemonClient.battleOverlay.opacityRatio.toFloat()
        if (opacity < .1f) return
        val primary = tile.resource == BattleGUI.fightResource
        val base = when (tile.resource) {
            BattleGUI.fightResource -> BattleUiTheme.primary
            BattleGUI.runResource, BattleGUI.forfeitResource -> BattleUiTheme.danger
            BattleGUI.bagResource -> BattleUiTheme.capture
            else -> BattleUiTheme.secondary
        }
        drawOption(context, tile.x, tile.y, tile.text, base, primary, focused, opacity)
    }

    internal fun drawOption(context: DrawContext, x: Int, y: Int, text: Text, base: BattleSurface,
                            primary: Boolean, focused: Boolean, opacity: Float = 1f) {
        val style = if (focused) base.copy(border = BattleUiTheme.FOCUS, borderWidth = 2) else base
        BattleSurfaceRenderer.draw(context, x, y, BattleOptionTile.OPTION_WIDTH, BattleOptionTile.OPTION_HEIGHT, style, opacity)
        val font = MinecraftClient.getInstance().textRenderer
        val scale = minOf(1f, (BattleOptionTile.OPTION_WIDTH - 16f) / font.getWidth(text).coerceAtLeast(1))
        val textX = x + (BattleOptionTile.OPTION_WIDTH - font.getWidth(text) * scale) / 2f
        val textY = y + (BattleOptionTile.OPTION_HEIGHT - font.fontHeight * scale) / 2f
        UIUtils.drawText(context, text.string, textX, textY, BattleSurfaceRenderer.withOpacity(if (primary) 0xFF071018.toInt() else BattleUiTheme.TEXT, opacity), scale)
    }

    @JvmStatic
    fun move(context: DrawContext, tile: BattleMoveSelection.MoveTile, focused: Boolean) {
        val opacity = tile.moveSelection.opacity
        if (opacity < .1f) return
        val typeColor = 0xFF000000.toInt() or
            ((tile.rgb.first * 255).toInt() shl 16) or
            ((tile.rgb.second * 255).toInt() shl 8) or (tile.rgb.third * 255).toInt()
        drawMove(context, tile.x, tile.y, tile.moveTemplate, tile.elementalType, typeColor,
            tile.move.pp, tile.move.maxpp, tile.selectable, focused, opacity)
    }

    internal fun drawMove(context: DrawContext, x: Float, y: Float, move: MoveTemplate,
                          type: ElementalType, typeColor: Int, pp: Int, maxPp: Int,
                          selectable: Boolean, focused: Boolean, opacity: Float = 1f) {
        val contentOpacity = opacity * if (selectable) 1f else .45f
        val style = BattleUiTheme.panel.copy(
            border = if (focused) BattleUiTheme.FOCUS else typeColor,
            borderWidth = if (focused) 2 else 1,
            cut = 4, corners = 0b1010
        )
        BattleSurfaceRenderer.draw(context, x.toInt(), y.toInt(), BattleMoveSelection.MOVE_WIDTH, BattleMoveSelection.MOVE_HEIGHT, style, contentOpacity)
        TypeIcon(x = x - 9, y = y + 2, type = type, opacity = contentOpacity).render(context)
        MoveCategoryIcon(x = x + 46, y = y + 14.5, category = move.damageCategory, opacity = contentOpacity).render(context)
        val font = MinecraftClient.getInstance().textRenderer
        val name = move.displayName.string
        val nameScale = minOf(.9f, 69f / font.getWidth(name).coerceAtLeast(1))
        UIUtils.drawText(context, name, x + 17, y + 3, BattleSurfaceRenderer.withOpacity(BattleUiTheme.TEXT, contentOpacity), nameScale)
        val label = if (pp == 100 && maxPp == 100) "—/—" else "$pp/$maxPp"
        val ppColor = when {
            pp == 0 -> BattleUiTheme.DANGER
            pp <= maxPp / 2 -> BattleUiTheme.FOCUS
            else -> BattleUiTheme.MUTED
        }
        UIUtils.drawText(context, label, x + 87 - font.getWidth(label) * .7f, y + 15, BattleSurfaceRenderer.withOpacity(ppColor, contentOpacity), .7f)
    }
}

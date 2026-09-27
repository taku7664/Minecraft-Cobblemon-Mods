package jbro.cobblemon.battleui.extended.ui.shared

import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.gui.TypeIcon
import com.cobblemon.mod.common.client.gui.MoveCategoryIcon
import com.cobblemon.mod.common.client.gui.battle.BattleGUI
import com.cobblemon.mod.common.client.gui.battle.widgets.BattleOptionTile
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection
import jbro.cobblemon.battleui.extended.UIUtils
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext

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
        val style = if (focused) base.copy(border = BattleUiTheme.FOCUS, borderWidth = 2) else base
        BattleSurfaceRenderer.draw(context, tile.x, tile.y, BattleOptionTile.OPTION_WIDTH, BattleOptionTile.OPTION_HEIGHT, style, opacity)
        val font = MinecraftClient.getInstance().textRenderer
        val scale = minOf(1f, (BattleOptionTile.OPTION_WIDTH - 16f) / font.getWidth(tile.text).coerceAtLeast(1))
        val textX = tile.x + (BattleOptionTile.OPTION_WIDTH - font.getWidth(tile.text) * scale) / 2f
        val textY = tile.y + (BattleOptionTile.OPTION_HEIGHT - font.fontHeight * scale) / 2f
        UIUtils.drawText(context, tile.text.string, textX, textY, BattleSurfaceRenderer.withOpacity(if (primary) 0xFF071018.toInt() else BattleUiTheme.TEXT, opacity), scale)
    }

    @JvmStatic
    fun move(context: DrawContext, tile: BattleMoveSelection.MoveTile, focused: Boolean) {
        val opacity = tile.moveSelection.opacity
        if (opacity < .1f) return
        val contentOpacity = opacity * if (tile.selectable) 1f else .45f
        val typeColor = 0xFF000000.toInt() or
            ((tile.rgb.first * 255).toInt() shl 16) or
            ((tile.rgb.second * 255).toInt() shl 8) or (tile.rgb.third * 255).toInt()
        val style = BattleUiTheme.panel.copy(
            border = if (focused) BattleUiTheme.FOCUS else typeColor,
            borderWidth = if (focused) 2 else 1,
            cut = 4, corners = 0b1010
        )
        val x = tile.x.toInt()
        val y = tile.y.toInt()
        BattleSurfaceRenderer.draw(context, x, y, BattleMoveSelection.MOVE_WIDTH, BattleMoveSelection.MOVE_HEIGHT, style, contentOpacity)
        TypeIcon(x = tile.x - 9, y = tile.y + 2, type = tile.elementalType, opacity = contentOpacity).render(context)
        MoveCategoryIcon(x = tile.x + 46, y = tile.y + 14.5, category = tile.moveTemplate.damageCategory, opacity = contentOpacity).render(context)
        val font = MinecraftClient.getInstance().textRenderer
        val name = tile.moveTemplate.displayName.string
        val nameScale = minOf(.9f, 69f / font.getWidth(name).coerceAtLeast(1))
        UIUtils.drawText(context, name, tile.x + 17, tile.y + 3, BattleSurfaceRenderer.withOpacity(BattleUiTheme.TEXT, contentOpacity), nameScale)
        val pp = tile.move
        val label = if (pp.pp == 100 && pp.maxpp == 100) "—/—" else "${pp.pp}/${pp.maxpp}"
        val ppColor = when {
            pp.pp == 0 -> BattleUiTheme.DANGER
            pp.pp <= pp.maxpp / 2 -> BattleUiTheme.FOCUS
            else -> BattleUiTheme.MUTED
        }
        UIUtils.drawText(context, label, tile.x + 87 - font.getWidth(label) * .7f, tile.y + 15, BattleSurfaceRenderer.withOpacity(ppColor, contentOpacity), .7f)
    }
}

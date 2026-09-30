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
import jbro.cobblemon.battleui.navigation.BattleScreenGeometry
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import kotlin.math.abs

/**
 * Command, move and back controls. Tiles anchored to the right screen edge are rounded only on their free left end.
 * Focus, from the mouse or the keyboard, eases in through [BattleFocusMotion]: the tile slides out with a small
 * overshoot and brightens, a halo in its accent color breathes behind it, its accent pill grows, and one cursor per
 * menu glides to it and nudges toward it.
 */
object BattleControlRenderer {
    private const val OPTION_RADIUS = 9
    private const val MOVE_RADIUS = 10
    private const val BACK_WIDTH = 29
    private const val BACK_HEIGHT = 17

    /** One cursor per menu: the owner is the widget that holds the menu's tiles. */
    private data class Group(val owner: Any, val kind: String)

    @JvmStatic
    fun back(context: DrawContext, x: Int, y: Int, hovered: Boolean) {
        val emphasis = BattleFocusMotion.emphasis(BACK_KEY, hovered)
        val extension = protrusion(emphasis)
        val corners = BattleCornerCuts(8, 8, 8, 8)
        drawDropShadow(context, x - extension, y, BACK_WIDTH + extension, BACK_HEIGHT, corners, 1f)
        drawFocusHalo(context, x - extension, y, BACK_WIDTH + extension, BACK_HEIGHT, corners, BattleUiTheme.CYAN, emphasis, 1f)
        BattleSurfaceRenderer.draw(context, x - extension, y, BACK_WIDTH + extension, BACK_HEIGHT,
            lit(BattleUiTheme.secondary.copy(cornerCuts = corners, rounded = true), emphasis))
        val ink = BattleSurfaceRenderer.interpolate(BattleUiTheme.TEXT, BattleUiTheme.CYAN, emphasis)
        // The arrow leans the way it goes as focus arrives.
        val arrowX = x + 9 - (emphasis * 2f).toInt()
        context.fill(arrowX, y + 8, arrowX + 11, y + 9, ink)
        for (step in 1..3) {
            context.fill(arrowX + step, y + 8 - step, arrowX + 1 + step, y + 9 - step, ink)
            context.fill(arrowX + step, y + 8 + step, arrowX + 1 + step, y + 9 + step, ink)
        }
    }

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
        val emphasis = BattleFocusMotion.emphasis(tile, focused)
        drawOption(context, tile.x, tile.y, tile.text, base, primary, emphasis, opacity)
        if (focused) drawCursor(context, Group(tile.battleGUI, "option"), tile.x - protrusion(emphasis),
            tile.y + BattleOptionTile.OPTION_HEIGHT / 2f, accentOf(base), opacity)
    }

    internal fun drawOption(context: DrawContext, x: Int, y: Int, text: Text, base: BattleSurface,
                            primary: Boolean, emphasis: Float, opacity: Float = 1f) {
        val extension = protrusion(emphasis)
        val left = x - extension
        val width = BattleOptionTile.OPTION_WIDTH + extension
        val height = BattleOptionTile.OPTION_HEIGHT
        val corners = BattleCornerCuts(topLeft = OPTION_RADIUS, bottomLeft = OPTION_RADIUS)
        val accent = accentOf(base)
        drawDropShadow(context, left, y, width, height, corners, opacity)
        drawFocusHalo(context, left, y, width, height, corners, accent, emphasis, opacity)
        BattleSurfaceRenderer.draw(context, left, y, width, height,
            lit(base.copy(borderWidth = 0, cornerCuts = corners, rounded = true), emphasis), opacity)
        val pillHeight = 12 + (6 * emphasis).toInt()
        BattleSurfaceRenderer.capsule(context, left + 6, y + (height - pillHeight) / 2, 3, pillHeight,
            if (primary) BattleSurfaceRenderer.interpolate(BattleUiTheme.PANEL_ALT, 0xFF06343A.toInt(), emphasis)
            else BattleSurfaceRenderer.interpolate(dim(accent), accent, emphasis), opacity)
        val font = MinecraftClient.getInstance().textRenderer
        val scale = minOf(1f, (BattleOptionTile.OPTION_WIDTH - 16f) / font.getWidth(text).coerceAtLeast(1))
        val textX = x + (BattleOptionTile.OPTION_WIDTH - font.getWidth(text) * scale) / 2f
        val textY = y + (height - font.fontHeight * scale) / 2f
        UIUtils.drawText(context, text.string, textX, textY, BattleSurfaceRenderer.withOpacity(if (primary) 0xFF071018.toInt() else BattleUiTheme.TEXT, opacity), scale)
    }

    @JvmStatic
    fun move(context: DrawContext, tile: BattleMoveSelection.MoveTile, focused: Boolean) {
        val opacity = tile.moveSelection.opacity
        if (opacity < .1f) return
        val typeColor = 0xFF000000.toInt() or
            ((tile.rgb.first * 255).toInt() shl 16) or
            ((tile.rgb.second * 255).toInt() shl 8) or (tile.rgb.third * 255).toInt()
        val emphasis = BattleFocusMotion.emphasis(tile, focused)
        drawMove(context, tile.x, tile.y, tile.moveTemplate, tile.elementalType, typeColor,
            tile.move.pp, tile.move.maxpp, tile.selectable, emphasis, opacity)
        if (focused) drawCursor(context, Group(tile.moveSelection, "move"), tile.x.toInt() - protrusion(emphasis),
            tile.y + BattleScreenGeometry.MOVE_HEIGHT / 2f, typeColor, opacity)
    }

    internal fun drawMove(context: DrawContext, x: Float, y: Float, move: MoveTemplate,
                          type: ElementalType, typeColor: Int, pp: Int, maxPp: Int,
                          selectable: Boolean, emphasis: Float, opacity: Float = 1f) {
        val contentOpacity = opacity * if (selectable) 1f else .95f
        val extension = protrusion(emphasis)
        val left = x.toInt() - extension
        val top = y.toInt()
        val width = BattleScreenGeometry.MOVE_WIDTH + extension
        val height = BattleScreenGeometry.MOVE_HEIGHT
        val corners = BattleCornerCuts(topLeft = MOVE_RADIUS, bottomLeft = MOVE_RADIUS)
        drawDropShadow(context, left, top, width, height, corners, opacity)
        // The halo takes the move's type color, so the focused move reads as its type at a glance.
        drawFocusHalo(context, left, top, width, height, corners, typeColor, emphasis, opacity)
        BattleSurfaceRenderer.draw(context, left, top, width, height,
            lit(BattleUiTheme.panel.copy(cornerCuts = corners, rounded = true), emphasis), contentOpacity)
        val pillHeight = 16 + (6 * emphasis).toInt()
        BattleSurfaceRenderer.capsule(context, left + 6, top + (height - pillHeight) / 2, 3, pillHeight,
            if (selectable) typeColor else dim(typeColor), opacity)
        TypeIcon(x = x + 15, y = y + 19, type = type, small = true,
            opacity = if (selectable) opacity else opacity * .5f).render(context)
        MoveCategoryIcon(x = x + 29, y = y + 19, category = move.damageCategory,
            opacity = if (selectable) opacity else opacity * .5f).render(context)
        val font = MinecraftClient.getInstance().textRenderer
        val name = move.displayName.string
        UIUtils.drawText(context, font.trimToWidth(name, 114), x + 14, y + 4,
            BattleSurfaceRenderer.withOpacity(if (selectable) BattleUiTheme.TEXT else BattleUiTheme.MUTED, opacity), 1f)
        val label = if (pp == 100 && maxPp == 100) "—/—" else "$pp/$maxPp"
        val ppColor = when {
            pp == 0 -> BattleUiTheme.DANGER
            pp <= maxPp / 2 -> BattleUiTheme.FOCUS
            else -> BattleUiTheme.MUTED
        }
        UIUtils.drawText(context, label, x + BattleScreenGeometry.MOVE_WIDTH - 7 - font.getWidth(label), y + 19,
            BattleSurfaceRenderer.withOpacity(ppColor, opacity), 1f)
    }

    /** Previews draw a settled state: fully focused or at rest. */
    internal fun drawOption(context: DrawContext, x: Int, y: Int, text: Text, base: BattleSurface,
                            primary: Boolean, focused: Boolean, opacity: Float = 1f) =
        drawOption(context, x, y, text, base, primary, if (focused) 1f else 0f, opacity)

    internal fun drawMove(context: DrawContext, x: Float, y: Float, move: MoveTemplate,
                          type: ElementalType, typeColor: Int, pp: Int, maxPp: Int,
                          selectable: Boolean, focused: Boolean, opacity: Float = 1f) =
        drawMove(context, x, y, move, type, typeColor, pp, maxPp, selectable, if (focused) 1f else 0f, opacity)

    /** How far a tile slides out at this emphasis, overshooting a little on the way; the hitbox has the full slide. */
    @JvmStatic
    fun protrusion(emphasis: Float): Int =
        (BattleScreenGeometry.FOCUS_PROTRUSION * BattleFocusMotion.overshoot(emphasis) + .5f).toInt().coerceAtLeast(0)

    /** A breathing halo in [accent] behind a focused surface; nothing at rest. */
    @JvmStatic
    fun drawFocusHalo(context: DrawContext, x: Int, y: Int, width: Int, height: Int, corners: BattleCornerCuts,
                      accent: Int, emphasis: Float, opacity: Float) {
        if (emphasis <= .02f) return
        val strength = emphasis * (.5f + .25f * BattleFocusMotion.pulse())
        BattleSurfaceRenderer.glow(context, x, y, width, height, corners, accent, 4, opacity * strength)
    }

    /** A soft shadow under a control, so it separates from bright sky and terrain behind it. */
    @JvmStatic
    fun drawDropShadow(context: DrawContext, x: Int, y: Int, width: Int, height: Int, corners: BattleCornerCuts,
                       opacity: Float) {
        BattleSurfaceRenderer.draw(context, x, y + 2, width, height,
            BattleSurface(SHADOW, SHADOW, cornerCuts = corners, rounded = true), opacity)
    }

    /**
     * The menu cursor: a small arrow whose tip sits left of the focused choice. It glides between choices on
     * [group]'s spring and nudges toward the choice it points at.
     */
    @JvmStatic
    fun drawCursor(context: DrawContext, group: Any, tipX: Int, centerY: Float, accent: Int, opacity: Float) {
        val y = BattleFocusMotion.cursor(group, centerY)
        val x = tipX - 3f + BattleFocusMotion.bob() * 1.5f
        drawArrow(context, x + 1f, y + 1f, BattleSurfaceRenderer.withOpacity(0x8C000000.toInt(), opacity))
        drawArrow(context, x, y, BattleSurfaceRenderer.withOpacity(
            BattleSurfaceRenderer.interpolate(accent, 0xFFFFFFFF.toInt(), .55f), opacity))
    }

    /** A right-pointing arrow with its tip at ([x], [y]): 6 wide and 11 tall, its slanted edges anti-aliased. */
    private fun drawArrow(context: DrawContext, x: Float, y: Float, color: Int) {
        context.matrices.push()
        context.matrices.translate(x, y, 0f)
        val alpha = color ushr 24
        for (row in -5..5) {
            val reach = 6f * (1f - (abs(row) + .5f) / 5.5f)
            val full = reach.toInt()
            if (full > 0) context.fill(-6, row, -6 + full, row + 1, color)
            val partial = reach - full
            if (partial > .05f) context.fill(-6 + full, row, -5 + full, row + 1,
                (color and 0xFFFFFF) or ((alpha * partial).toInt() shl 24))
        }
        context.matrices.pop()
    }

    private fun lit(style: BattleSurface, emphasis: Float): BattleSurface =
        if (emphasis <= 0f) style
        else style.copy(top = BattleSurfaceRenderer.interpolate(style.top, lighten(style.top), emphasis),
            bottom = BattleSurfaceRenderer.interpolate(style.bottom, lighten(style.bottom), emphasis))

    private fun accentOf(base: BattleSurface): Int = when (base) {
        BattleUiTheme.primary -> BattleUiTheme.CYAN
        BattleUiTheme.danger -> BattleUiTheme.DANGER
        BattleUiTheme.capture -> BattleUiTheme.PURPLE
        else -> BattleUiTheme.CYAN
    }

    private fun dim(color: Int): Int = BattleSurfaceRenderer.interpolate(color, 0xFF1A2A3C.toInt(), .45f)

    private fun lighten(color: Int): Int {
        val red = minOf(255, ((color ushr 16) and 255) + 22)
        val green = minOf(255, ((color ushr 8) and 255) + 26)
        val blue = minOf(255, (color and 255) + 30)
        return (color and 0xFF000000.toInt()) or (red shl 16) or (green shl 8) or blue
    }

    private const val SHADOW = 0x3A000000
    private val BACK_KEY = Any()
}

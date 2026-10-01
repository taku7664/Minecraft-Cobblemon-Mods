package jbro.cobblemon.ui.extended.ui.shared

import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.api.moves.MoveTemplate
import com.cobblemon.mod.common.api.types.ElementalType
import com.cobblemon.mod.common.client.gui.TypeIcon
import com.cobblemon.mod.common.client.gui.MoveCategoryIcon
import com.cobblemon.mod.common.client.gui.battle.BattleGUI
import com.cobblemon.mod.common.client.gui.battle.widgets.BattleOptionTile
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection
import jbro.cobblemon.ui.extended.UIUtils
import jbro.cobblemon.ui.navigation.BattleScreenGeometry
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import kotlin.math.abs

/**
 * Command, move and back controls, in the shapes and colors of the theme in use ([BattleUiThemes]). Every theme
 * keeps the tiles' places, sizes and hitboxes. Focus, from the mouse or the keyboard, eases in through
 * [BattleFocusMotion]: the tile slides out with a small overshoot and either brightens behind a breathing halo or,
 * in a theme with a [BattleUiPalette.focusFill], turns that color; one cursor per menu glides to it.
 */
object BattleControlRenderer {
    private const val OPTION_RADIUS = 9
    private const val MOVE_RADIUS = 10
    private const val BACK_WIDTH = 29
    private const val BACK_HEIGHT = 17
    /** A pill stands this far off the screen edge it would otherwise touch. */
    private const val PILL_GAP = 4
    /** A move pill's height: one line, inside the taller two-line hitbox. */
    private const val SLIM_MOVE_HEIGHT = 22

    /** One cursor per menu: the owner is the widget that holds the menu's tiles. */
    private data class Group(val owner: Any, val kind: String)

    /** The four battle commands, each with its own color in a theme. */
    private enum class Command { FIGHT, SWITCH, BAG, RUN }

    @JvmStatic
    fun back(context: GuiGraphics, x: Int, y: Int, hovered: Boolean) {
        val palette = BattleUiTheme.palette
        val emphasis = BattleFocusMotion.emphasis(BACK_KEY, hovered)
        val extension = protrusion(emphasis)
        val corners = BattleCornerCuts(8, 8, 8, 8)
        drawDropShadow(context, x - extension, y, BACK_WIDTH + extension, BACK_HEIGHT, corners, 1f)
        if (palette.focusFill == null) {
            drawFocusHalo(context, x - extension, y, BACK_WIDTH + extension, BACK_HEIGHT, corners, BattleUiTheme.CYAN, emphasis, 1f)
        }
        BattleSurfaceRenderer.draw(context, x - extension, y, BACK_WIDTH + extension, BACK_HEIGHT,
            focused(BattleUiTheme.secondary.copy(cornerCuts = corners, rounded = true), emphasis))
        val ink = BattleSurfaceRenderer.interpolate(palette.commandText,
            palette.focusFill?.let { palette.focusText } ?: BattleUiTheme.CYAN, emphasis)
        // The arrow leans the way it goes as focus arrives.
        val arrowX = x + 9 - (emphasis * 2f).toInt()
        context.fill(arrowX, y + 8, arrowX + 11, y + 9, ink)
        for (step in 1..3) {
            context.fill(arrowX + step, y + 8 - step, arrowX + 1 + step, y + 9 - step, ink)
            context.fill(arrowX + step, y + 8 + step, arrowX + 1 + step, y + 9 + step, ink)
        }
    }

    @JvmStatic
    fun option(context: GuiGraphics, tile: BattleOptionTile, focused: Boolean) {
        val opacity = CobblemonClient.battleOverlay.opacityRatio.toFloat()
        if (opacity < .1f) return
        val command = when (tile.resource) {
            BattleGUI.fightResource -> Command.FIGHT
            BattleGUI.runResource, BattleGUI.forfeitResource -> Command.RUN
            BattleGUI.bagResource -> Command.BAG
            else -> Command.SWITCH
        }
        val emphasis = BattleFocusMotion.emphasis(tile, focused)
        drawOption(context, tile.x, tile.y, tile.text, command, emphasis, opacity)
        if (focused) drawCursor(context, Group(tile.battleGUI, "option"), tile.x - protrusion(emphasis),
            tile.y + BattleOptionTile.OPTION_HEIGHT / 2f, accentOf(command), opacity)
    }

    private fun drawOption(context: GuiGraphics, x: Int, y: Int, text: Component, command: Command,
                           emphasis: Float, opacity: Float) {
        val palette = BattleUiTheme.palette
        val pill = palette.controlShape == BattleControlShape.PILL
        val extension = protrusion(emphasis)
        val left = x - extension
        val height = BattleOptionTile.OPTION_HEIGHT
        val width = BattleOptionTile.OPTION_WIDTH + extension - if (pill) PILL_GAP else 0
        val corners = if (pill) BattleCornerCuts(height / 2, height / 2, height / 2, height / 2)
            else BattleCornerCuts(topLeft = OPTION_RADIUS, bottomLeft = OPTION_RADIUS)
        val accent = accentOf(command)
        val base = when (command) {
            Command.FIGHT -> BattleUiTheme.primary
            Command.RUN -> BattleUiTheme.danger
            Command.BAG -> BattleUiTheme.capture
            Command.SWITCH -> BattleUiTheme.secondary
        }
        val primary = command == Command.FIGHT && palette.focusFill == null
        drawDropShadow(context, left, y, width, height, corners, opacity)
        if (palette.focusFill == null) drawFocusHalo(context, left, y, width, height, corners, accent, emphasis, opacity)
        BattleSurfaceRenderer.draw(context, left, y, width, height,
            focused(base.copy(borderWidth = 0, cornerCuts = corners, rounded = true), emphasis), opacity)
        when (palette.commandAccent) {
            BattleCommandAccent.BAR -> {
                val pillHeight = 12 + (6 * emphasis).toInt()
                BattleSurfaceRenderer.capsule(context, left + 6, y + (height - pillHeight) / 2, 3, pillHeight,
                    if (primary) BattleSurfaceRenderer.interpolate(BattleUiTheme.PANEL_ALT, 0xFF06343A.toInt(), emphasis)
                    else BattleSurfaceRenderer.interpolate(dim(accent), accent, emphasis), opacity)
            }
            BattleCommandAccent.END_CAP -> {
                // The command's color as a disc in the pill's far end, ringed in white once the pill turns dark.
                val disc = height - 8
                val discX = left + width - 4 - disc
                if (emphasis > .02f) BattleSurfaceRenderer.capsule(context, discX - 1, y + 3, disc + 2, disc + 2,
                    0xFFFFFFFF.toInt(), opacity * emphasis)
                BattleSurfaceRenderer.capsule(context, discX, y + 4, disc, disc, accent, opacity)
            }
        }
        val font = Minecraft.getInstance().font
        val ink = if (primary) palette.primaryText else palette.focusFill?.let {
            BattleSurfaceRenderer.interpolate(palette.commandText, palette.focusText, emphasis)
        } ?: palette.commandText
        val scale = minOf(1f, (BattleOptionTile.OPTION_WIDTH - 16f) / font.width(text).coerceAtLeast(1))
        // Sword and Shield set the label at the pill's start; the rounded tiles center it.
        val textX = if (pill) left + 14f else x + (BattleOptionTile.OPTION_WIDTH - font.width(text) * scale) / 2f
        val textY = y + (height - font.lineHeight * scale) / 2f
        UIUtils.drawText(context, text.string, textX, textY, BattleSurfaceRenderer.withOpacity(ink, opacity), scale)
    }

    @JvmStatic
    fun move(context: GuiGraphics, tile: BattleMoveSelection.MoveTile, focused: Boolean) {
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

    private fun drawMove(context: GuiGraphics, x: Float, y: Float, move: MoveTemplate,
                         type: ElementalType, typeColor: Int, pp: Int, maxPp: Int,
                         selectable: Boolean, emphasis: Float, opacity: Float) {
        val palette = BattleUiTheme.palette
        val pill = palette.controlShape == BattleControlShape.PILL
        val typed = palette.moveFill == BattleMoveFill.TYPE
        val contentOpacity = opacity * if (selectable) 1f else .95f
        val extension = protrusion(emphasis)
        val left = x.toInt() - extension
        // A pill holds the move on one line, slimmer than its hitbox and centered in it.
        val height = if (pill) SLIM_MOVE_HEIGHT else BattleScreenGeometry.MOVE_HEIGHT
        val top = y.toInt() + (BattleScreenGeometry.MOVE_HEIGHT - height) / 2
        val width = BattleScreenGeometry.MOVE_WIDTH + extension - if (pill) PILL_GAP else 0
        val corners = if (pill) BattleCornerCuts(height / 2, height / 2, height / 2, height / 2)
            else BattleCornerCuts(topLeft = MOVE_RADIUS, bottomLeft = MOVE_RADIUS)
        drawDropShadow(context, left, top, width, height, corners, opacity)
        if (!typed) {
            // The halo takes the move's type color, so the focused move reads as its type at a glance.
            drawFocusHalo(context, left, top, width, height, corners, typeColor, emphasis, opacity)
            BattleSurfaceRenderer.draw(context, left, top, width, height,
                focused(BattleUiTheme.panel.copy(cornerCuts = corners, rounded = true), emphasis), contentOpacity)
            val pillHeight = 16 + (6 * emphasis).toInt()
            BattleSurfaceRenderer.capsule(context, left + 6, top + (height - pillHeight) / 2, 3, pillHeight,
                if (selectable) typeColor else dim(typeColor), opacity)
        } else {
            // Sword and Shield: the move on its type's color, deep at rest and brighter when chosen, ringed in white.
            val rest = BattleSurfaceRenderer.interpolate(typeColor, 0xFF1F1F1F.toInt(), if (selectable) .42f else .66f)
            val lit = BattleSurfaceRenderer.interpolate(rest, BattleSurfaceRenderer.interpolate(typeColor,
                0xFF1F1F1F.toInt(), .14f), emphasis)
            if (emphasis > .02f) BattleSurfaceRenderer.draw(context, left - 1, top - 1, width + 2, height + 2,
                BattleSurface(0xFFFFFFFF.toInt(), cornerCuts = BattleCornerCuts(height / 2 + 1, height / 2 + 1,
                    height / 2 + 1, height / 2 + 1)), opacity * emphasis)
            BattleSurfaceRenderer.draw(context, left, top, width, height, BattleSurface(lit,
                BattleSurfaceRenderer.interpolate(lit, 0xFF000000.toInt(), .18f), cornerCuts = corners), contentOpacity)
        }
        val font = Minecraft.getInstance().font
        // Two lines, name over icons and PP; or one line in a pill: icons, name, PP.
        val iconY = if (pill) top + (height - 9) / 2f else y + 19
        val iconX = if (pill) x + 12 else x + 15
        TypeIcon(x = iconX, y = iconY, type = type, small = true,
            opacity = if (selectable) opacity else opacity * .5f).render(context)
        MoveCategoryIcon(x = iconX + 14, y = iconY, category = move.damageCategory,
            opacity = if (selectable) opacity else opacity * .5f).render(context)
        val name = move.displayName.string
        val nameInk = when {
            typed -> 0xFFFFFFFF.toInt()
            selectable -> BattleUiTheme.TEXT
            else -> BattleUiTheme.MUTED
        }
        val label = if (pp == 100 && maxPp == 100) "—/—" else "$pp/$maxPp"
        val ppX = x + BattleScreenGeometry.MOVE_WIDTH - 7 - font.width(label) - if (pill) PILL_GAP + 3 else 0
        val nameX = if (pill) iconX + 30 else x + 14
        val nameY = if (pill) iconY else y + 4
        // On one line a long name ends in an ellipsis; the move tooltip still names it in full.
        val shown = if (pill) fit(font, name, (ppX - nameX - 4).toInt()) else font.plainSubstrByWidth(name, 114)
        // White on a type color keeps a soft shadow, as the games set it, so bright types stay readable.
        if (typed) UIUtils.drawText(context, shown, nameX + 1, nameY + 1,
            BattleSurfaceRenderer.withOpacity(0x66000000, opacity), 1f)
        UIUtils.drawText(context, shown, nameX, nameY, BattleSurfaceRenderer.withOpacity(nameInk, opacity), 1f)
        val ppColor = when {
            pp == 0 -> if (typed) 0xFFFFB3B3.toInt() else BattleUiTheme.DANGER
            pp <= maxPp / 2 -> if (typed) 0xFFFFE08A.toInt() else BattleUiTheme.FOCUS
            else -> if (typed) 0xFFF2F2F2.toInt() else BattleUiTheme.MUTED
        }
        UIUtils.drawText(context, label, ppX, if (pill) iconY else y + 19,
            BattleSurfaceRenderer.withOpacity(ppColor, opacity), 1f)
    }

    private fun fit(font: net.minecraft.client.gui.Font, text: String, width: Int): String =
        if (font.width(text) <= width) text
        else font.plainSubstrByWidth(text, (width - font.width("…")).coerceAtLeast(0)) + "…"

    /** How far a tile slides out at this emphasis, overshooting a little on the way; the hitbox has the full slide. */
    @JvmStatic
    fun protrusion(emphasis: Float): Int =
        (BattleScreenGeometry.FOCUS_PROTRUSION * BattleFocusMotion.overshoot(emphasis) + .5f).toInt().coerceAtLeast(0)

    /** A breathing halo in [accent] behind a focused surface; nothing at rest. */
    @JvmStatic
    fun drawFocusHalo(context: GuiGraphics, x: Int, y: Int, width: Int, height: Int, corners: BattleCornerCuts,
                      accent: Int, emphasis: Float, opacity: Float) {
        if (emphasis <= .02f) return
        val strength = emphasis * (.5f + .25f * BattleFocusMotion.pulse())
        BattleSurfaceRenderer.glow(context, x, y, width, height, corners, accent, 4, opacity * strength)
    }

    /** A soft shadow under a control, so it separates from bright sky and terrain behind it. */
    @JvmStatic
    fun drawDropShadow(context: GuiGraphics, x: Int, y: Int, width: Int, height: Int, corners: BattleCornerCuts,
                       opacity: Float) {
        val shadow = BattleUiTheme.palette.dropShadow
        BattleSurfaceRenderer.draw(context, x, y + 2, width, height,
            BattleSurface(shadow, shadow, cornerCuts = corners, rounded = true), opacity)
    }

    /**
     * The menu cursor: a small arrow whose tip sits left of the focused choice. It glides between choices on
     * [group]'s spring and nudges toward the choice it points at.
     */
    @JvmStatic
    fun drawCursor(context: GuiGraphics, group: Any, tipX: Int, centerY: Float, accent: Int, opacity: Float) {
        val palette = BattleUiTheme.palette
        val y = BattleFocusMotion.cursor(group, centerY)
        val x = tipX - 3f + BattleFocusMotion.bob() * 1.5f
        val color = palette.cursor ?: BattleSurfaceRenderer.interpolate(accent, 0xFFFFFFFF.toInt(), .55f)
        if (palette.cursor != null) {
            // A dark arrow gets a white outline instead of a shadow, so it holds against the scene.
            val outline = BattleSurfaceRenderer.withOpacity(0xFFFFFFFF.toInt(), opacity)
            drawArrow(context, x + 1f, y, outline)
            drawArrow(context, x - 1f, y, outline)
            drawArrow(context, x, y + 1f, outline)
            drawArrow(context, x, y - 1f, outline)
        } else drawArrow(context, x + 1f, y + 1f, BattleSurfaceRenderer.withOpacity(0x8C000000.toInt(), opacity))
        drawArrow(context, x, y, BattleSurfaceRenderer.withOpacity(color, opacity))
    }

    /** A right-pointing arrow with its tip at ([x], [y]): 6 wide and 11 tall, its slanted edges anti-aliased. */
    private fun drawArrow(context: GuiGraphics, x: Float, y: Float, color: Int) {
        context.pose().pushPose()
        context.pose().translate(x, y, 0f)
        val alpha = color ushr 24
        for (row in -5..5) {
            val reach = 6f * (1f - (abs(row) + .5f) / 5.5f)
            val full = reach.toInt()
            if (full > 0) context.fill(-6, row, -6 + full, row + 1, color)
            val partial = reach - full
            if (partial > .05f) context.fill(-6 + full, row, -5 + full, row + 1,
                (color and 0xFFFFFF) or ((alpha * partial).toInt() shl 24))
        }
        context.pose().popPose()
    }

    /** A control at [emphasis]: turned the theme's focus color, or lightened when the theme has none. */
    private fun focused(style: BattleSurface, emphasis: Float): BattleSurface {
        if (emphasis <= 0f) return style
        val fill = BattleUiTheme.palette.focusFill
        return if (fill != null) style.copy(top = BattleSurfaceRenderer.interpolate(style.top, fill, emphasis),
            bottom = BattleSurfaceRenderer.interpolate(style.bottom, fill, emphasis))
        else style.copy(top = BattleSurfaceRenderer.interpolate(style.top, lighten(style.top), emphasis),
            bottom = BattleSurfaceRenderer.interpolate(style.bottom, lighten(style.bottom), emphasis))
    }

    private fun accentOf(command: Command): Int {
        val palette = BattleUiTheme.palette
        return when (command) {
            Command.FIGHT -> palette.fightAccent
            Command.SWITCH -> palette.switchAccent
            Command.BAG -> palette.bagAccent
            Command.RUN -> palette.runAccent
        }
    }

    private fun dim(color: Int): Int = BattleSurfaceRenderer.interpolate(color, 0xFF1A2A3C.toInt(), .45f)

    private fun lighten(color: Int): Int {
        val red = minOf(255, ((color ushr 16) and 255) + 22)
        val green = minOf(255, ((color ushr 8) and 255) + 26)
        val blue = minOf(255, (color and 255) + 30)
        return (color and 0xFF000000.toInt()) or (red shl 16) or (green shl 8) or blue
    }

    private val BACK_KEY = Any()
}

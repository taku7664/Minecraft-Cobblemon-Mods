package jbro.cobblemon.ui.extended.ui.shared

import jbro.cobblemon.ui.navigation.BattleScreenGeometry
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component

/** The preview and Cobblemon's confirmation selection share this drawing path. */
object BattleForfeitRenderer {
    @JvmStatic
    fun draw(context: GuiGraphics, width: Int, height: Int, title: Component, detail: Component,
             acceptLabel: Component, cancelLabel: Component, focused: Int = -1, opacity: Float = 1f) {
        val panel = BattleScreenGeometry.forfeitPanel(width, height)
        val accept = BattleScreenGeometry.forfeitAccept(width, height)
        val cancel = BattleScreenGeometry.forfeitCancel(width, height)
        BattleSurfaceRenderer.draw(context, panel.x(), panel.y(), panel.width(), panel.height(),
            BattleUiTheme.shell, opacity)
        val font = Minecraft.getInstance().font
        context.drawString(font, font.plainSubstrByWidth(title.string, panel.width() - 28), panel.x() + 14,
            panel.y() + 12, BattleSurfaceRenderer.withOpacity(BattleUiTheme.TEXT, opacity), false)
        context.drawString(font, font.plainSubstrByWidth(detail.string, panel.width() - 28), panel.x() + 14,
            panel.y() + 29, BattleSurfaceRenderer.withOpacity(BattleUiTheme.MUTED, opacity), false)
        val acceptEmphasis = drawChoice(context, accept, BattleUiTheme.danger, BattleUiTheme.DANGER, ACCEPT_KEY,
            focused == 0, opacity)
        val cancelEmphasis = drawChoice(context, cancel, BattleUiTheme.secondary, BattleUiTheme.CYAN, CANCEL_KEY,
            focused == 1, opacity)
        centered(context, font.plainSubstrByWidth(acceptLabel.string, accept.width() - 8), accept.x() + accept.width() / 2,
            accept.y() + 7, acceptEmphasis, opacity)
        centered(context, font.plainSubstrByWidth(cancelLabel.string, cancel.width() - 8), cancel.x() + cancel.width() / 2,
            cancel.y() + 7, cancelEmphasis, opacity)
    }

    private fun drawChoice(context: GuiGraphics, rect: jbro.cobblemon.ui.navigation.UiRect, base: BattleSurface,
                           accent: Int, key: Any, focused: Boolean, opacity: Float): Float {
        val emphasis = BattleFocusMotion.emphasis(key, focused)
        val pill = BattleCornerCuts(12, 12, 12, 12)
        val fill = BattleUiTheme.palette.focusFill
        BattleControlRenderer.drawDropShadow(context, rect.x(), rect.y(), rect.width(), rect.height(), pill, opacity)
        // A theme with a focus color turns the chosen button that color; the others lift it behind a halo.
        val style = if (fill != null) base.copy(top = BattleSurfaceRenderer.interpolate(base.top, fill, emphasis),
            bottom = BattleSurfaceRenderer.interpolate(base.bottom, fill, emphasis), cornerCuts = pill)
        else {
            BattleControlRenderer.drawFocusHalo(context, rect.x(), rect.y(), rect.width(), rect.height(), pill,
                accent, emphasis, opacity)
            base.copy(top = BattleSurfaceRenderer.interpolate(base.top, 0xFFFFFFFF.toInt(), .14f * emphasis),
                cornerCuts = pill)
        }
        BattleSurfaceRenderer.draw(context, rect.x(), rect.y(), rect.width(), rect.height(), style, opacity)
        return emphasis
    }

    private val ACCEPT_KEY = Any()
    private val CANCEL_KEY = Any()

    private fun centered(context: GuiGraphics, value: String, centerX: Int, y: Int, emphasis: Float, opacity: Float) {
        val font = Minecraft.getInstance().font
        val palette = BattleUiTheme.palette
        val ink = if (palette.focusFill != null)
            BattleSurfaceRenderer.interpolate(palette.commandText, palette.focusText, emphasis) else BattleUiTheme.TEXT
        context.drawString(font, value, centerX - font.width(value) / 2, y,
            BattleSurfaceRenderer.withOpacity(ink, opacity), false)
    }
}

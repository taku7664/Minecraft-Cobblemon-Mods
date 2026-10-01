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
        drawChoice(context, accept, BattleUiTheme.danger, BattleUiTheme.DANGER, ACCEPT_KEY, focused == 0, opacity)
        drawChoice(context, cancel, BattleUiTheme.secondary, BattleUiTheme.CYAN, CANCEL_KEY, focused == 1, opacity)
        centered(context, font.plainSubstrByWidth(acceptLabel.string, accept.width() - 8), accept.x() + accept.width() / 2,
            accept.y() + 7, opacity)
        centered(context, font.plainSubstrByWidth(cancelLabel.string, cancel.width() - 8), cancel.x() + cancel.width() / 2,
            cancel.y() + 7, opacity)
    }

    private fun drawChoice(context: GuiGraphics, rect: jbro.cobblemon.ui.navigation.UiRect, base: BattleSurface,
                           accent: Int, key: Any, focused: Boolean, opacity: Float) {
        val emphasis = BattleFocusMotion.emphasis(key, focused)
        val pill = BattleCornerCuts(12, 12, 12, 12)
        BattleControlRenderer.drawDropShadow(context, rect.x(), rect.y(), rect.width(), rect.height(), pill, opacity)
        BattleControlRenderer.drawFocusHalo(context, rect.x(), rect.y(), rect.width(), rect.height(), pill,
            accent, emphasis, opacity)
        val lift = BattleSurfaceRenderer.interpolate(base.top, 0xFFFFFFFF.toInt(), .14f * emphasis)
        BattleSurfaceRenderer.draw(context, rect.x(), rect.y(), rect.width(), rect.height(),
            base.copy(top = lift, cornerCuts = pill), opacity)
    }

    private val ACCEPT_KEY = Any()
    private val CANCEL_KEY = Any()

    private fun centered(context: GuiGraphics, value: String, centerX: Int, y: Int, opacity: Float) {
        val font = Minecraft.getInstance().font
        context.drawString(font, value, centerX - font.width(value) / 2, y,
            BattleSurfaceRenderer.withOpacity(BattleUiTheme.TEXT, opacity), false)
    }
}

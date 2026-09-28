package jbro.cobblemon.battleui.extended.ui.shared

import jbro.cobblemon.battleui.navigation.BattleScreenGeometry
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text

/** The preview and Cobblemon's confirmation selection share this drawing path. */
object BattleForfeitRenderer {
    @JvmStatic
    fun draw(context: DrawContext, width: Int, height: Int, title: Text, detail: Text,
             acceptLabel: Text, cancelLabel: Text, focused: Int = -1, opacity: Float = 1f) {
        val panel = BattleScreenGeometry.forfeitPanel(width, height)
        val accept = BattleScreenGeometry.forfeitAccept(width, height)
        val cancel = BattleScreenGeometry.forfeitCancel(width, height)
        BattleSurfaceRenderer.draw(context, panel.x(), panel.y(), panel.width(), panel.height(),
            BattleUiTheme.shell, opacity)
        val font = MinecraftClient.getInstance().textRenderer
        context.drawText(font, font.trimToWidth(title.string, panel.width() - 28), panel.x() + 14,
            panel.y() + 12, BattleSurfaceRenderer.withOpacity(BattleUiTheme.TEXT, opacity), false)
        context.drawText(font, font.trimToWidth(detail.string, panel.width() - 28), panel.x() + 14,
            panel.y() + 29, BattleSurfaceRenderer.withOpacity(BattleUiTheme.MUTED, opacity), false)
        BattleSurfaceRenderer.draw(context, accept.x(), accept.y(), accept.width(), accept.height(),
            BattleUiTheme.danger.copy(cut = 5, corners = 0b0101,
                top = if (focused == 0) 0xFFBD586B.toInt() else BattleUiTheme.danger.top), opacity)
        BattleSurfaceRenderer.draw(context, cancel.x(), cancel.y(), cancel.width(), cancel.height(),
            BattleUiTheme.secondary.copy(top = if (focused == 1) 0xFF2E526D.toInt() else BattleUiTheme.secondary.top,
                bottom = if (focused == 1) 0xFF2E526D.toInt() else BattleUiTheme.secondary.bottom,
                cut = 4, corners = 0b1010), opacity)
        centered(context, font.trimToWidth(acceptLabel.string, accept.width() - 8), accept.x() + accept.width() / 2,
            accept.y() + 7, opacity)
        centered(context, font.trimToWidth(cancelLabel.string, cancel.width() - 8), cancel.x() + cancel.width() / 2,
            cancel.y() + 7, opacity)
    }

    private fun centered(context: DrawContext, value: String, centerX: Int, y: Int, opacity: Float) {
        val font = MinecraftClient.getInstance().textRenderer
        context.drawText(font, value, centerX - font.getWidth(value) / 2, y,
            BattleSurfaceRenderer.withOpacity(BattleUiTheme.TEXT, opacity), false)
    }
}

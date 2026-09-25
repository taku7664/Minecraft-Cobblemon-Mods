package jbro.cobblemon.morebattlecontent.leaguechallenge.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

internal class LeagueChallengeDevelopmentScreen : Screen(text("title")) {
    override fun init() {
        addRenderableWidget(
            Button.builder(Component.translatable("gui.done")) { onClose() }
                .bounds((width - BUTTON_WIDTH) / 2, height - 36, BUTTON_WIDTH, 20)
                .build()
        )
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics, mouseX, mouseY, partialTick)
        super.render(graphics, mouseX, mouseY, partialTick)

        graphics.drawCenteredString(font, title, width / 2, 28, TITLE_COLOR)
        drawCenteredLines(graphics, text("heading"), 56, PRIMARY_TEXT_COLOR)
        drawCenteredLines(graphics, text("status"), 86, SECONDARY_TEXT_COLOR)
        drawCenteredLines(graphics, text("warning"), 116, WARNING_TEXT_COLOR)
    }

    private fun drawCenteredLines(graphics: GuiGraphics, component: Component, startY: Int, color: Int) {
        var y = startY
        for (line in font.split(component, minOf(width - 32, 420))) {
            graphics.drawCenteredString(font, line, width / 2, y, color)
            y += 11
        }
    }

    companion object {
        private const val BUTTON_WIDTH = 160
        private const val TITLE_COLOR = 0xFFF3D98B.toInt()
        private const val PRIMARY_TEXT_COLOR = 0xFFFFFFFF.toInt()
        private const val SECONDARY_TEXT_COLOR = 0xFFD7DEEA.toInt()
        private const val WARNING_TEXT_COLOR = 0xFFFFC266.toInt()

        private fun text(suffix: String): Component = Component.translatable(
            "screen.cobblemon_more_battle_content_league_challenge.dev.$suffix"
        )
    }
}

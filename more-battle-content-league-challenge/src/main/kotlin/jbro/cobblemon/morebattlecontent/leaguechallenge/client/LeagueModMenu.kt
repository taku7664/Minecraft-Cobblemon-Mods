package jbro.cobblemon.morebattlecontent.leaguechallenge.client

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** Server rules live in data packs/CLC, not misleading client-side difficulty switches. */
class LeagueModMenu : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> = ConfigScreenFactory { parent -> LeagueConfigurationScreen(parent) }
}

private class LeagueConfigurationScreen(private val parent: Screen?) : Screen(text("title")) {
    override fun init() {
        addRenderableWidget(Button.builder(Component.translatable("gui.done")) { onClose() }
            .bounds(width / 2 - 75, height - 36, 150, 20).build())
    }
    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        graphics.drawCenteredString(font, title, width / 2, 24, 0xFFFFFF)
        var y = 56
        for (key in listOf("datapack", "caps", "rewards", "reload")) {
            val lines = font.split(text(key), (width - 48).coerceAtLeast(100))
            lines.forEach { graphics.drawString(font, it, 24, y, 0xCCCCCC); y += 12 }
            y += 12
        }
    }
    override fun onClose() { minecraft?.setScreen(parent) }
}

private fun text(key: String) = Component.translatable("config.cobblemon_more_battle_content_league_challenge.$key")

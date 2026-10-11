package jbro.cobblemon.partysidebar.client

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import jbro.cobblemon.partysidebar.CobblemonPartySidebar
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class SidebarModMenu : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> = ConfigScreenFactory { parent -> SidebarSettingsScreen(parent) }
}

private class SidebarSettingsScreen(private val parent: Screen?) :
    Screen(Component.translatable("config.cobblemon_party_sidebar.title")) {
    private var enabled = SidebarConfig.enabled
    private var showTooltip = SidebarConfig.showTooltip
    private var status: Component? = null

    override fun init() {
        val left = width / 2 - 100
        val top = height / 2 - 50
        addRenderableWidget(CycleButton.onOffBuilder(enabled)
            .create(left, top, 200, 20, Component.translatable("config.cobblemon_party_sidebar.enabled")) { _, value -> enabled = value })
        addRenderableWidget(CycleButton.onOffBuilder(showTooltip)
            .create(left, top + 24, 200, 20, Component.translatable("config.cobblemon_party_sidebar.tooltip")) { _, value -> showTooltip = value })
        addRenderableWidget(Button.builder(Component.translatable("gui.done")) { save() }.bounds(left, top + 64, 98, 20).build())
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel")) { onClose() }.bounds(left + 102, top + 64, 98, 20).build())
    }

    private fun save() {
        try {
            SidebarConfig.save(enabled, showTooltip)
            onClose()
        } catch (failure: Exception) {
            CobblemonPartySidebar.LOGGER.warn("Could not save party sidebar settings", failure)
            status = Component.translatable("config.cobblemon_party_sidebar.save_failed")
        }
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 72, 0xFFFFFF)
        status?.let { graphics.drawCenteredString(font, it, width / 2, height / 2 + 40, 0xFF6060) }
    }

    override fun onClose() {
        minecraft?.setScreen(parent)
    }
}

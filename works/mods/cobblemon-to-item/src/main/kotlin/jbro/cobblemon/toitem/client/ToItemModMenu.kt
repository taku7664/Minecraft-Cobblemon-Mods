package jbro.cobblemon.toitem.client

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import jbro.cobblemon.toitem.config.ToItemConfig
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class ToItemModMenu : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> = ConfigScreenFactory { ConfigScreen(it) }
}

private class ConfigScreen(private val parent: Screen) : Screen(text("title")) {
    private var permission = ToItemConfig.permissionLevel
    private var tooltip = ToItemConfig.showTooltip
    private var failed = false

    override fun init() {
        val left = (width - 240) / 2
        val top = height / 2 - 30
        addRenderableWidget(Button.builder(permissionText()) { button ->
            permission = (permission + 1) % 5
            button.message = permissionText()
        }.bounds(left, top, 240, 20).build())
        addRenderableWidget(CycleButton.onOffBuilder(tooltip).create(left, top + 24, 240, 20, text("tooltip")) { _, value -> tooltip = value })
        addRenderableWidget(Button.builder(Component.translatable("gui.done")) {
            try { ToItemConfig.save(permission, tooltip); onClose() } catch (_: Exception) { failed = true }
        }.bounds(left, top + 52, 118, 20).build())
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel")) { onClose() }
            .bounds(left + 122, top + 52, 118, 20).build())
    }

    private fun permissionText() = Component.translatable("config.cobblemon_to_item.permission", permission)
    override fun onClose() { minecraft?.setScreen(parent) }
    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics, mouseX, mouseY, partialTick)
        super.render(graphics, mouseX, mouseY, partialTick)
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 72, 0xFFFFFF)
        graphics.drawCenteredString(font, text("scope"), width / 2, height / 2 + 54, 0xAAAAAA)
        if (failed) graphics.drawCenteredString(font, text("save_failed"), width / 2, height / 2 + 70, 0xFF5555)
    }
    companion object { private fun text(key: String) = Component.translatable("config.cobblemon_to_item.$key") }
}

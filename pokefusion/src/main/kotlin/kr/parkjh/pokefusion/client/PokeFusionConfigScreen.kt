package kr.parkjh.pokefusion.client

import kr.parkjh.pokefusion.PokeFusionConfig
import kr.parkjh.pokefusion.PokeFusionConfigManager
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class PokeFusionConfigScreen(private val parent: Screen) : Screen(text("title")) {
    private var selectedPermissionLevel = PokeFusionConfigManager.current().commandPermissionLevel
    private var status: Component = Component.empty()
    private lateinit var permissionButton: Button

    override fun init() {
        val left = (width - BUTTON_WIDTH) / 2
        val top = height / 2 - 20
        permissionButton = addRenderableWidget(
            Button.builder(permissionText()) { button ->
                selectedPermissionLevel = if (selectedPermissionLevel >= PokeFusionConfig.MAX_PERMISSION_LEVEL) {
                    PokeFusionConfig.MIN_PERMISSION_LEVEL
                } else {
                    selectedPermissionLevel + 1
                }
                button.message = permissionText()
                status = Component.empty()
            }.bounds(left, top, BUTTON_WIDTH, 20).build()
        )
        addRenderableWidget(
            Button.builder(text("save")) {
                try {
                    PokeFusionConfigManager.save(PokeFusionConfig(selectedPermissionLevel))
                    onClose()
                } catch (_: Exception) {
                    status = text("save_failed")
                }
            }.bounds(left, top + 32, (BUTTON_WIDTH - 4) / 2, 20).build()
        )
        addRenderableWidget(
            Button.builder(Component.translatable("gui.cancel")) { onClose() }
                .bounds(left + (BUTTON_WIDTH + 4) / 2, top + 32, (BUTTON_WIDTH - 4) / 2, 20).build()
        )
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics, mouseX, mouseY, partialTick)
        super.render(graphics, mouseX, mouseY, partialTick)
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 78, 0xFFFFFFFF.toInt())
        drawCenteredLines(graphics, text("description"), height / 2 - 58, 0xFFD7DEEA.toInt())
        drawCenteredLines(graphics, text("scope"), height / 2 + 42, 0xFFFFCC66.toInt())
        if (status.string.isNotEmpty()) {
            graphics.drawCenteredString(font, status, width / 2, height / 2 + 76, 0xFFFF6666.toInt())
        }
    }

    override fun onClose() {
        minecraft?.setScreen(parent)
    }

    private fun permissionText(): Component = Component.translatable(
        "config.pokefusion.command_permission_level.value",
        selectedPermissionLevel
    )

    private fun drawCenteredLines(graphics: GuiGraphics, component: Component, startY: Int, color: Int) {
        var y = startY
        for (line in font.split(component, minOf(width - 32, 440))) {
            graphics.drawCenteredString(font, line, width / 2, y, color)
            y += 10
        }
    }

    companion object {
        private const val BUTTON_WIDTH = 240
        private fun text(suffix: String): Component = Component.translatable("config.pokefusion.$suffix")
    }
}

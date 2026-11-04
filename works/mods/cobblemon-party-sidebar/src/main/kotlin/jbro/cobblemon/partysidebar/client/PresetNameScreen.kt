package jbro.cobblemon.partysidebar.client

import jbro.cobblemon.partysidebar.network.PresetActionPayload
import jbro.cobblemon.partysidebar.preset.PresetData
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * Renames the current preset: a text box over the dimmed inventory. Enter saves, Esc goes back unchanged; either way
 * the inventory it came from returns. A blank name shows as the default "Preset N".
 */
class PresetNameScreen(private val previous: Screen, private val current: String) :
    Screen(Component.translatable("screen.cobblemon_party_sidebar.rename")) {

    private lateinit var box: EditBox

    override fun init() {
        box = EditBox(font, width / 2 - BOX_WIDTH / 2, height / 2 - 10, BOX_WIDTH, 20, title)
        box.setMaxLength(PresetData.MAX_NAME_LENGTH)
        box.value = current
        addRenderableWidget(box)
        setInitialFocus(box)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            ClientPlayNetworking.send(PresetActionPayload(PresetActionPayload.Action.RENAME, name = box.value))
            onClose()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 26, 0xFFFFFF)
        graphics.drawCenteredString(font, Component.translatable("screen.cobblemon_party_sidebar.rename_hint"), width / 2, height / 2 + 16, 0xA0A0A0)
    }

    override fun onClose() {
        minecraft?.setScreen(previous)
    }

    override fun isPauseScreen() = false

    private companion object {
        const val BOX_WIDTH = 160
    }
}

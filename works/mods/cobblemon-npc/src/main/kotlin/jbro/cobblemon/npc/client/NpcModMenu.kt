package jbro.cobblemon.npc.client

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import jbro.cobblemon.npc.CobblemonNpc
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class NpcModMenu : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> =
        ConfigScreenFactory { parent -> NpcClientSettingsScreen(parent) }
}

private class NpcClientSettingsScreen(private val parent: Screen?) :
    NpcEditorScreen(Component.translatable("screen.cobblemon_npc.client_settings")) {
    private var camera = NpcClientConfig.dialogueCamera

    override fun init() {
        super.init()
        val panelWidth = 320.coerceAtMost(width - 16)
        val left = (width - panelWidth) / 2
        val top = (height - 140) / 2
        panel(left, top, panelWidth, 140, title)
        button(left + 12, top + 34, cameraLabel(), width = panelWidth - 24) {
            camera = !camera
            rebuildWidgets()
        }
        label(Component.translatable("config.cobblemon_npc.camera_hint"), left + 12, top + 65)
        label(Component.translatable("config.cobblemon_npc.content_hint"), left + 12, top + 80)
        button(left + 12, top + 105, Component.translatable("gui.done")) {
            try {
                NpcClientConfig.save(camera)
                onClose()
            } catch (failure: Exception) {
                CobblemonNpc.LOGGER.warn("Could not save NPC client settings", failure)
                showResult(false, Component.translatable("config.cobblemon_npc.save_failed"))
            }
        }
        button(left + panelWidth - 80, top + 105, Component.translatable("gui.cancel")) { onClose() }
    }

    private fun cameraLabel() = Component.translatable("config.cobblemon_npc.camera",
        Component.translatable(if (camera) "options.on" else "options.off"))

    override fun onClose() { minecraft?.setScreen(parent) }
}

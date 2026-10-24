package jbro.cobblemon.policy.client

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.nio.file.Files

class InquiryModMenu : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> = ConfigScreenFactory { InquiryConfigScreen(it) }
}

private class InquiryConfigScreen(private val parent: Screen) : Screen(text("title")) {
    private val path = FabricLoader.getInstance().configDir.resolve("jbro-policy-inquiry-review.json")
    private var failed = false
    private var original = runCatching {
        if (Files.exists(path)) JsonParser.parseString(Files.readString(path)).asJsonObject else JsonObject()
    }.getOrElse { failed = true; JsonObject() }
    private var enabled = runCatching { original.get("enabled")?.asBoolean ?: false }.getOrDefault(false)
    private var automatic = runCatching { original.get("autoResolve")?.asBoolean ?: true }.getOrDefault(true)

    override fun init() {
        val left = (width - 260) / 2
        val top = height / 2 - 35
        addRenderableWidget(CycleButton.onOffBuilder(enabled).create(left, top, 260, 20, text("enabled")) { _, value -> enabled = value })
        addRenderableWidget(CycleButton.onOffBuilder(automatic).create(left, top + 24, 260, 20, text("automatic")) { _, value -> automatic = value })
        addRenderableWidget(Button.builder(Component.translatable("gui.done")) {
            try {
                // Read again so saving these two switches preserves any concurrent CLI/channel configuration edits.
                val current = if (Files.exists(path)) JsonParser.parseString(Files.readString(path)).asJsonObject else JsonObject()
                current.addProperty("enabled", enabled)
                current.addProperty("autoResolve", automatic)
                Files.createDirectories(path.parent)
                Files.writeString(path, GsonBuilder().setPrettyPrinting().create().toJson(current))
                onClose()
            } catch (_: Exception) { failed = true }
        }.bounds(left, top + 52, 128, 20).build().apply { active = !failed })
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel")) { onClose() }.bounds(left + 132, top + 52, 128, 20).build())
    }

    override fun onClose() { minecraft?.setScreen(parent) }
    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics, mouseX, mouseY, partialTick)
        super.render(graphics, mouseX, mouseY, partialTick)
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 70, 0xFFFFFF)
        graphics.drawCenteredString(font, text("local"), width / 2, height / 2 + 52, 0xAAAAAA)
        graphics.drawCenteredString(font, text("rewards"), width / 2, height / 2 + 66, 0xAAAAAA)
        if (failed) graphics.drawCenteredString(font, text("failed"), width / 2, height / 2 + 82, 0xFF5555)
    }

    companion object { private fun text(key: String) = Component.translatable("config.jbro_policy.inquiry.$key") }
}

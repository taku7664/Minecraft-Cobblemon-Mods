package jbro.cobblemon.mcc.client

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import jbro.cobblemon.mcc.betterai.config.*
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class BetterAiModMenu : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> = ConfigScreenFactory { BetterAiOptimizationScreen(it) }
}

private class BetterAiOptimizationScreen(private val parent: Screen?) : Screen(text("title")) {
    private val path = FabricLoader.getInstance().configDir.resolve("more-cobblemon-contents")
        .resolve(LocalOptimizationConfigStore.FILE_NAME)
    private var failed = false
    private var config = runCatching { LocalOptimizationConfigStore.loadOrCreate(path) }.getOrElse {
        failed = true; LocalOptimizationConfig()
    }
    override fun init() {
        val left = width / 2 - 150
        addRenderableWidget(CycleButton.onOffBuilder(config.simpleFixedHpReactions)
            .create(left, 62, 300, 20, text("fixed_hp")) { _, enabled -> config = config.copy(simpleFixedHpReactions = enabled) })
        addRenderableWidget(CycleButton.onOffBuilder(config.simplePoisonEvaluation)
            .create(left, 88, 300, 20, text("poison")) { _, enabled -> config = config.copy(simplePoisonEvaluation = enabled) })
        addRenderableWidget(Button.builder(Component.translatable("gui.done")) {
            try { LocalOptimizationConfigStore.save(path, config); onClose() } catch (_: Exception) { failed = true }
        }.bounds(left, height - 36, 146, 20).build())
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel")) { onClose() }
            .bounds(left + 154, height - 36, 146, 20).build())
    }
    override fun onClose() { minecraft?.setScreen(parent) }
    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics, mouseX, mouseY, partialTick)
        super.render(graphics, mouseX, mouseY, partialTick)
        graphics.drawCenteredString(font, title, width / 2, 28, 0xFFFFFF)
        var y = 122
        for (key in listOf("fixed_hp_help", "poison_help", "scope")) {
            font.split(text(key), (width - 48).coerceAtLeast(100)).forEach {
                graphics.drawString(font, it, 24, y, 0xCCCCCC); y += 12
            }
            y += 8
        }
        if (failed) graphics.drawCenteredString(font, text("failed"), width / 2, height - 52, 0xFF5555)
    }
}

private fun text(key: String) = Component.translatable("config.more_cobblemon_contents.betterai.$key")

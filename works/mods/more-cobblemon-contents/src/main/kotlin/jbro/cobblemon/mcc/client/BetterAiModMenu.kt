package jbro.cobblemon.mcc.client

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import jbro.cobblemon.mcc.betterai.config.*
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
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
        for (doubles in listOf(false, true)) {
            val x = left + if (doubles) 154 else 0
            val settings = if (doubles) config.double else config.single
            fun update(change: (LocalOptimizationSettings) -> LocalOptimizationSettings) {
                config = if (doubles) config.copy(double = change(config.double)) else config.copy(single = change(config.single))
            }
            addRenderableWidget(CycleButton.onOffBuilder(settings.simpleFixedHpReactions)
                .create(x, 62, 146, 20, text("fixed_hp")) { _, enabled -> update { it.copy(simpleFixedHpReactions = enabled) } }
                .also { it.setTooltip(Tooltip.create(text("fixed_hp_help"))) })
            addRenderableWidget(CycleButton.onOffBuilder(settings.simplePoisonEvaluation)
                .create(x, 86, 146, 20, text("poison")) { _, enabled -> update { it.copy(simplePoisonEvaluation = enabled) } }
                .also { it.setTooltip(Tooltip.create(text("poison_help"))) })
            addRenderableWidget(CycleButton.onOffBuilder(settings.simpleHpMatchups)
                .create(x, 110, 146, 20, text("matchup")) { _, enabled -> update { it.copy(simpleHpMatchups = enabled) } }
                .also { it.setTooltip(Tooltip.create(text("matchup_help"))) })
            addRenderableWidget(CycleButton.onOffBuilder(settings.simpleForecastMatchups)
                .create(x, 134, 146, 20, text("forecast")) { _, enabled -> update { it.copy(simpleForecastMatchups = enabled) } }
                .also { it.setTooltip(Tooltip.create(text("forecast_help").copy().append("\n\n").append(text("scope")))) })
            addRenderableWidget(CycleButton.onOffBuilder(settings.simpleStatusMatchups)
                .create(x, 158, 146, 20, text("simple_status")) { _, enabled -> update { it.copy(simpleStatusMatchups = enabled) } }
                .also { it.setTooltip(Tooltip.create(text("simple_status_help"))) })
            addRenderableWidget(CycleButton.onOffBuilder(settings.simpleActingChance)
                .create(x, 182, 146, 20, text("simple_acting")) { _, enabled -> update { it.copy(simpleActingChance = enabled) } }
                .also { it.setTooltip(Tooltip.create(text("simple_acting_help"))) })
        }
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
        graphics.drawCenteredString(font, text("single"), width / 2 - 77, 48, 0xFFFFFF)
        graphics.drawCenteredString(font, text("double"), width / 2 + 77, 48, 0xFFFFFF)
        var y = 212
        for (key in listOf("scope")) {
            font.split(text(key), (width - 48).coerceAtLeast(100)).take(((height - 60 - y) / 12).coerceAtLeast(0)).forEach {
                graphics.drawString(font, it, 24, y, 0xCCCCCC); y += 12
            }
            y += 8
        }
        if (failed) graphics.drawCenteredString(font, text("failed"), width / 2, height - 52, 0xFF5555)
    }
}

private fun text(key: String) = Component.translatable("config.more_cobblemon_contents.betterai.$key")

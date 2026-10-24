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
    private var page = 0
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
            fun toggle(index: Int, enabled: Boolean, key: String, change: (LocalOptimizationSettings, Boolean) -> LocalOptimizationSettings) {
                if (index / 4 != page) return
                addRenderableWidget(CycleButton.onOffBuilder(enabled)
                    .create(x, 62 + (index % 4) * 24, 146, 20, text(key)) { _, value -> update { change(it, value) } }
                    .also { it.setTooltip(Tooltip.create(text(key + "_help"))) })
            }
            toggle(0, settings.simpleFixedHpReactions, "fixed_hp") { value, enabled -> value.copy(simpleFixedHpReactions = enabled) }
            toggle(1, settings.simplePoisonEvaluation, "poison") { value, enabled -> value.copy(simplePoisonEvaluation = enabled) }
            toggle(2, settings.simpleHpMatchups, "matchup") { value, enabled -> value.copy(simpleHpMatchups = enabled) }
            toggle(3, settings.simpleForecastMatchups, "forecast") { value, enabled -> value.copy(simpleForecastMatchups = enabled) }
            toggle(4, settings.simpleStatusMatchups, "simple_status") { value, enabled -> value.copy(simpleStatusMatchups = enabled) }
            toggle(5, settings.simpleActingChance, "simple_acting") { value, enabled -> value.copy(simpleActingChance = enabled) }
            toggle(6, settings.representativeHitOutcomes, "representative_hit") { value, enabled -> value.copy(representativeHitOutcomes = enabled) }
            toggle(7, settings.simpleSwitchContinuations, "simple_switch") { value, enabled -> value.copy(simpleSwitchContinuations = enabled) }
        }
        addRenderableWidget(Button.builder(Component.translatable("config.more_cobblemon_contents.betterai.options_page", page + 1)) {
            page = 1 - page; clearWidgets(); init()
        }.bounds(left, height - 62, 300, 20).build())
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
        var y = 164
        for (key in listOf("scope")) {
            font.split(text(key), (width - 48).coerceAtLeast(100)).take(((height - 84 - y) / 12).coerceAtLeast(0)).forEach {
                graphics.drawString(font, it, 24, y, 0xCCCCCC); y += 12
            }
            y += 8
        }
        if (failed) graphics.drawCenteredString(font, text("failed"), width / 2, height - 52, 0xFF5555)
    }
}

private fun text(key: String) = Component.translatable("config.more_cobblemon_contents.betterai.$key")

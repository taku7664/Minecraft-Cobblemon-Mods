package jbro.cobblemon.battleui.extended

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import me.shedaniel.clothconfig2.api.ConfigBuilder
import me.shedaniel.clothconfig2.api.Requirement
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

/**
 * Mod Menu integration for config screen.
 * This class is only loaded when Mod Menu is present (registered as modmenu entrypoint).
 */
class ModMenuIntegration : ModMenuApi {

    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> {
        return ConfigScreenFactory { parent -> createConfigScreen(parent) }
    }

    private fun createConfigScreen(parent: Screen): Screen {
        val builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Text.translatable("cobblemon_battle_ui.config.title"))
            .setSavingRunnable { PanelConfig.save() }

        val general = builder.getOrCreateCategory(Text.translatable("cobblemon_battle_ui.config.category.features"))
        val entryBuilder = builder.entryBuilder()

        // Battle Info Panel toggle
        general.addEntry(
            entryBuilder.startBooleanToggle(
                Text.translatable("cobblemon_battle_ui.config.enableBattleInfoPanel"),
                PanelConfig.enableBattleInfoPanel
            )
                .setDefaultValue(true)
                .setTooltip(Text.translatable("cobblemon_battle_ui.config.enableBattleInfoPanel.tooltip"))
                .setSaveConsumer { value -> PanelConfig.setEnableBattleInfoPanel(value) }
                .build()
        )

        val battleLogEntry = entryBuilder.startBooleanToggle(
            Text.translatable("cobblemon_battle_ui.config.enableBattleLog"),
            PanelConfig.enableBattleLog
        )
            .setDefaultValue(true)
            .setTooltip(Text.translatable("cobblemon_battle_ui.config.enableBattleLog.tooltip"))
            .setSaveConsumer { value -> PanelConfig.setEnableBattleLog(value) }
            .build()
        general.addEntry(battleLogEntry)

        general.addEntry(
            entryBuilder.startBooleanToggle(
                Text.translatable("cobblemon_battle_ui.config.enableBattleLogDamagePercentages"),
                PanelConfig.enableBattleLogDamagePercentages
            )
                .setDefaultValue(true)
                .setTooltip(Text.translatable("cobblemon_battle_ui.config.enableBattleLogDamagePercentages.tooltip"))
                .setSaveConsumer { value -> PanelConfig.setEnableBattleLogDamagePercentages(value) }
                .setRequirement(Requirement.isTrue(battleLogEntry))
                .build()
        )

        // Move Tooltips toggle
        general.addEntry(
            entryBuilder.startBooleanToggle(
                Text.translatable("cobblemon_battle_ui.config.enableMoveTooltips"),
                PanelConfig.enableMoveTooltips
            )
                .setDefaultValue(true)
                .setTooltip(Text.translatable("cobblemon_battle_ui.config.enableMoveTooltips.tooltip"))
                .setSaveConsumer { value -> PanelConfig.setEnableMoveTooltips(value) }
                .build()
        )

        // Tooltip Display Options category
        val tooltipOptions = builder.getOrCreateCategory(Text.translatable("cobblemon_battle_ui.config.category.tooltipOptions"))

        // Show Tera Type toggle
        tooltipOptions.addEntry(
            entryBuilder.startBooleanToggle(
                Text.translatable("cobblemon_battle_ui.config.showTeraType"),
                PanelConfig.showTeraType
            )
                // Disabled by default due to noisiness
                .setDefaultValue(false)
                .setTooltip(Text.translatable("cobblemon_battle_ui.config.showTeraType.tooltip"))
                .setSaveConsumer { value -> PanelConfig.setShowTeraType(value) }
                .build()
        )

        // Show Stat Ranges toggle
        tooltipOptions.addEntry(
            entryBuilder.startBooleanToggle(
                Text.translatable("cobblemon_battle_ui.config.showStatRanges"),
                PanelConfig.showStatRanges
            )
                .setDefaultValue(true)
                .setTooltip(Text.translatable("cobblemon_battle_ui.config.showStatRanges.tooltip"))
                .setSaveConsumer { value -> PanelConfig.setShowStatRanges(value) }
                .build()
        )

        tooltipOptions.addEntry(
            entryBuilder.startBooleanToggle(
                Text.translatable("cobblemon_battle_ui.config.showOpponentSpeedRange"),
                PanelConfig.showOpponentSpeedRange
            )
                .setDefaultValue(true)
                .setTooltip(Text.translatable("cobblemon_battle_ui.config.showOpponentSpeedRange.tooltip"))
                .setSaveConsumer { value -> PanelConfig.setShowOpponentSpeedRange(value) }
                .build()
        )

        // Show Base Crit Rate toggle
        tooltipOptions.addEntry(
            entryBuilder.startBooleanToggle(
                Text.translatable("cobblemon_battle_ui.config.showBaseCritRate"),
                PanelConfig.showBaseCritRate
            )
                // Disabled by default due to noisiness
                .setDefaultValue(false)
                .setTooltip(Text.translatable("cobblemon_battle_ui.config.showBaseCritRate.tooltip"))
                .setSaveConsumer { value -> PanelConfig.setShowBaseCritRate(value) }
                .build()
        )

        return builder.build()
    }
}

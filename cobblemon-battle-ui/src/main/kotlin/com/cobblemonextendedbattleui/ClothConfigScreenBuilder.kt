package jbro.cobblemon.battleui.extended

import me.shedaniel.clothconfig2.api.ConfigBuilder
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

/** Cloth Config-backed settings screen. Loaded only after its mod-presence check succeeds. */
object ClothConfigScreenBuilder {
    fun create(parent: Screen): Screen {
        val builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Text.translatable("cobblemon_battle_ui.config.title"))
            .setSavingRunnable { PanelConfig.save() }
        val entries = builder.entryBuilder()
        val features = builder.getOrCreateCategory(Text.translatable("cobblemon_battle_ui.config.category.features"))

        features.addEntry(entries.startBooleanToggle(
            Text.translatable("cobblemon_battle_ui.config.enableTeamIndicators"), PanelConfig.enableTeamIndicators
        ).setDefaultValue(true)
            .setTooltip(Text.translatable("cobblemon_battle_ui.config.enableTeamIndicators.tooltip"))
            .setSaveConsumer(PanelConfig::setEnableTeamIndicators).build())
        features.addEntry(entries.startBooleanToggle(
            Text.translatable("cobblemon_battle_ui.config.teamIndicatorRepositioning"),
            PanelConfig.teamIndicatorRepositioningEnabled
        ).setDefaultValue(true)
            .setTooltip(Text.translatable("cobblemon_battle_ui.config.teamIndicatorRepositioning.tooltip"))
            .setSaveConsumer(PanelConfig::setTeamIndicatorRepositioningEnabled).build())
        features.addEntry(entries.startEnumSelector(
            Text.translatable("cobblemon_battle_ui.config.teamIndicatorOrientation"),
            PanelConfig.TeamIndicatorOrientation::class.java,
            PanelConfig.teamIndicatorOrientation
        ).setDefaultValue(PanelConfig.TeamIndicatorOrientation.HORIZONTAL)
            .setEnumNameProvider { Text.translatable("cobblemon_battle_ui.config.orientation.${it.name.lowercase()}") }
            .setSaveConsumer(PanelConfig::setTeamIndicatorOrientation).build())
        features.addEntry(entries.startFloatField(
            Text.translatable("cobblemon_battle_ui.config.teamIndicatorScale"), PanelConfig.teamIndicatorScale
        ).setDefaultValue(1.0f).setMin(PanelConfig.MIN_FONT_SCALE).setMax(PanelConfig.MAX_FONT_SCALE)
            .setSaveConsumer(PanelConfig::setTeamIndicatorScale).build())
        features.addEntry(entries.startBooleanToggle(
            Text.translatable("cobblemon_battle_ui.config.enableBattleInfoPanel"), PanelConfig.enableBattleInfoPanel
        ).setDefaultValue(true)
            .setTooltip(Text.translatable("cobblemon_battle_ui.config.enableBattleInfoPanel.tooltip"))
            .setSaveConsumer(PanelConfig::setEnableBattleInfoPanel).build())

        features.addEntry(entries.startBooleanToggle(
            Text.translatable("cobblemon_battle_ui.config.enableMoveTooltips"), PanelConfig.enableMoveTooltips
        ).setDefaultValue(true)
            .setTooltip(Text.translatable("cobblemon_battle_ui.config.enableMoveTooltips.tooltip"))
            .setSaveConsumer(PanelConfig::setEnableMoveTooltips).build())

        val sizing = builder.getOrCreateCategory(Text.translatable("cobblemon_battle_ui.config.category.sizing"))
        sizing.addEntry(scaleEntry(entries, "tooltipFontScale", PanelConfig.tooltipFontScale, PanelConfig::setTooltipFontScale))
        sizing.addEntry(scaleEntry(entries, "moveTooltipFontScale", PanelConfig.moveTooltipFontScale, PanelConfig::setMoveTooltipFontScale))

        val tooltip = builder.getOrCreateCategory(Text.translatable("cobblemon_battle_ui.config.category.tooltipOptions"))
        tooltip.addEntry(toggle(entries, "showTeraType", PanelConfig.showTeraType, false, PanelConfig::setShowTeraType))
        tooltip.addEntry(toggle(entries, "showStatRanges", PanelConfig.showStatRanges, true, PanelConfig::setShowStatRanges))
        tooltip.addEntry(toggle(entries, "showOpponentSpeedRange", PanelConfig.showOpponentSpeedRange, true, PanelConfig::setShowOpponentSpeedRange))
        tooltip.addEntry(toggle(entries, "showBaseCritRate", PanelConfig.showBaseCritRate, false, PanelConfig::setShowBaseCritRate))
        return builder.build()
    }

    private fun scaleEntry(entries: ConfigEntryBuilder, key: String, value: Float, save: (Float) -> Unit) =
        entries.startFloatField(Text.translatable("cobblemon_battle_ui.config.$key"), value)
            .setDefaultValue(1.0f).setMin(PanelConfig.MIN_FONT_SCALE).setMax(PanelConfig.MAX_FONT_SCALE)
            .setSaveConsumer(save).build()

    private fun toggle(
        entries: ConfigEntryBuilder,
        key: String,
        value: Boolean,
        defaultValue: Boolean,
        save: (Boolean) -> Unit
    ) = entries.startBooleanToggle(Text.translatable("cobblemon_battle_ui.config.$key"), value)
        .setDefaultValue(defaultValue)
        .setTooltip(Text.translatable("cobblemon_battle_ui.config.$key.tooltip"))
        .setSaveConsumer(save).build()
}

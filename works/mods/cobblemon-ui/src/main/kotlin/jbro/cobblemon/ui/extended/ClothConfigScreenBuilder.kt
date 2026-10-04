package jbro.cobblemon.ui.extended

import me.shedaniel.clothconfig2.api.ConfigBuilder
import jbro.cobblemon.ui.extended.ui.shared.BattleUiThemes
import jbro.cobblemon.uikit.UiPalettePreset
import jbro.cobblemon.uikit.UiThemeStyle
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** Cloth Config-backed settings screen. Loaded only after its mod-presence check succeeds. */
object ClothConfigScreenBuilder {
    fun create(parent: Screen): Screen {
        val builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Component.translatable("cobblemon_ui.config.title"))
            .setSavingRunnable { PanelConfig.save() }
        val entries = builder.entryBuilder()
        // The theme comes first, so it is the tab the settings open on.
        val theme = builder.getOrCreateCategory(Component.translatable("cobblemon_ui.config.category.theme"))
        theme.addEntry(entries.startSelector(Component.translatable("cobblemon_ui.config.battleTheme"),
            BattleUiThemes.all.map { it.id }.toTypedArray(), PanelConfig.battleTheme)
            .setDefaultValue(BattleUiThemes.CHAMPIONS.id)
            .setNameProvider { Component.translatable("cobblemon_ui.config.battleTheme.$it") }
            .setTooltip(Component.translatable("cobblemon_ui.config.battleTheme.tooltip"))
            .setSaveConsumer(PanelConfig::setBattleTheme).build())
        theme.addEntry(toggle(entries, "enableBattleEntryTransition", PanelConfig.enableBattleEntryTransition, true,
            PanelConfig::setEnableBattleEntryTransition))
        // The MCC hub, the dialogue box and the HUD buttons share one look; the default is listed first.
        val styles = listOf(UiThemeStyle.DS_WINDOW, UiThemeStyle.PIXEL_FRAME).map { it.id }
        theme.addEntry(entries.startSelector(Component.translatable("cobblemon_ui.config.uiStyle"),
            styles.toTypedArray(), PanelConfig.uiStyle)
            .setDefaultValue(UiThemeStyle.DS_WINDOW.id)
            .setNameProvider { Component.translatable("cobblemon_ui.config.uiStyle.option", styles.indexOf(it) + 1) }
            .setTooltip(Component.translatable("cobblemon_ui.config.uiStyle.tooltip"))
            .setSaveConsumer(PanelConfig::setUiStyle).build())
        val palettes = UiPalettePreset.entries.map { it.id }
        theme.addEntry(entries.startSelector(Component.translatable("cobblemon_ui.config.uiPalette"),
            palettes.toTypedArray(), PanelConfig.uiPalette)
            .setDefaultValue(UiPalettePreset.TOWER_LOBBY.id)
            .setNameProvider { Component.translatable("cobblemon_ui.config.uiPalette.option", palettes.indexOf(it) + 1) }
            .setTooltip(Component.translatable("cobblemon_ui.config.uiPalette.tooltip"))
            .setSaveConsumer(PanelConfig::setUiPalette).build())

        val features = builder.getOrCreateCategory(Component.translatable("cobblemon_ui.config.category.features"))

        features.addEntry(entries.startBooleanToggle(
            Component.translatable("cobblemon_ui.config.enableTeamIndicators"), PanelConfig.enableTeamIndicators
        ).setDefaultValue(true)
            .setTooltip(Component.translatable("cobblemon_ui.config.enableTeamIndicators.tooltip"))
            .setSaveConsumer(PanelConfig::setEnableTeamIndicators).build())
        features.addEntry(entries.startBooleanToggle(
            Component.translatable("cobblemon_ui.config.enableBattleInfoPanel"), PanelConfig.enableBattleInfoPanel
        ).setDefaultValue(true)
            .setTooltip(Component.translatable("cobblemon_ui.config.enableBattleInfoPanel.tooltip"))
            .setSaveConsumer(PanelConfig::setEnableBattleInfoPanel).build())

        features.addEntry(entries.startBooleanToggle(
            Component.translatable("cobblemon_ui.config.enableMoveTooltips"), PanelConfig.enableMoveTooltips
        ).setDefaultValue(true)
            .setTooltip(Component.translatable("cobblemon_ui.config.enableMoveTooltips.tooltip"))
            .setSaveConsumer(PanelConfig::setEnableMoveTooltips).build())

        val sizing = builder.getOrCreateCategory(Component.translatable("cobblemon_ui.config.category.sizing"))
        sizing.addEntry(scaleEntry(entries, "logFontScale", PanelConfig.logFontScale, PanelConfig::setLogFontScale))
        sizing.addEntry(scaleEntry(entries, "tooltipFontScale", PanelConfig.tooltipFontScale, PanelConfig::setTooltipFontScale))
        sizing.addEntry(scaleEntry(entries, "moveTooltipFontScale", PanelConfig.moveTooltipFontScale, PanelConfig::setMoveTooltipFontScale))

        val tooltip = builder.getOrCreateCategory(Component.translatable("cobblemon_ui.config.category.tooltipOptions"))
        tooltip.addEntry(toggle(entries, "showTeraType", PanelConfig.showTeraType, false, PanelConfig::setShowTeraType))
        tooltip.addEntry(toggle(entries, "showStatRanges", PanelConfig.showStatRanges, true, PanelConfig::setShowStatRanges))
        tooltip.addEntry(toggle(entries, "showOpponentSpeedRange", PanelConfig.showOpponentSpeedRange, true, PanelConfig::setShowOpponentSpeedRange))
        tooltip.addEntry(toggle(entries, "showBaseCritRate", PanelConfig.showBaseCritRate, false, PanelConfig::setShowBaseCritRate))
        return builder.build()
    }

    private fun scaleEntry(entries: ConfigEntryBuilder, key: String, value: Float, save: (Float) -> Unit) =
        entries.startFloatField(Component.translatable("cobblemon_ui.config.$key"), value)
            .setDefaultValue(1.0f).setMin(PanelConfig.MIN_FONT_SCALE).setMax(PanelConfig.MAX_FONT_SCALE)
            .setSaveConsumer(save).build()

    private fun toggle(
        entries: ConfigEntryBuilder,
        key: String,
        value: Boolean,
        defaultValue: Boolean,
        save: (Boolean) -> Unit
    ) = entries.startBooleanToggle(Component.translatable("cobblemon_ui.config.$key"), value)
        .setDefaultValue(defaultValue)
        .setTooltip(Component.translatable("cobblemon_ui.config.$key.tooltip"))
        .setSaveConsumer(save).build()
}

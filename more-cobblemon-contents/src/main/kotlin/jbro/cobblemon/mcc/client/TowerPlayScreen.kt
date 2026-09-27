package jbro.cobblemon.mcc.client

import java.util.UUID
import jbro.cobblemon.mcc.api.rules.MajorBattleMechanic
import jbro.cobblemon.mcc.internal.hub.BattleHubContent
import jbro.cobblemon.mcc.internal.tower.TowerBattleFormat
import jbro.cobblemon.mcc.internal.tower.network.TowerPlayIntentPayload
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayMutationResult
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayInteractionPolicy
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayPartySlot
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayPhase
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayScreenController
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayViewState
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

internal enum class TowerLegendaryClassOption(
    val allowed: Boolean,
    val translationKey: String,
    val compactTranslationKey: String,
) {
    DISALLOWED(
        false,
        "screen.more_cobblemon_contents.tower.legendary_class.disallowed",
        "screen.more_cobblemon_contents.tower.legendary_class.disallowed.compact",
    ),
    ALLOWED(
        true,
        "screen.more_cobblemon_contents.tower.legendary_class.allowed",
        "screen.more_cobblemon_contents.tower.legendary_class.allowed.compact",
    ),
}

internal class TowerPlayScreen(
    initialState: TowerPlayViewState,
) : MccTabbedContentScreen(
    Component.translatable("screen.more_cobblemon_contents.tower.title"),
    BattleHubContent.BATTLE_TOWER,
) {
    private val portraits = MccPokemonPortraitRenderer()
    private val controller = TowerPlayScreenController(initialState) { intent ->
        TowerPlayClientNetworking.send(TowerPlayIntentPayload(intent))
    }

    override fun init() {
        buildWidgets()
    }

    override fun render(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        val state = controller.state
        MccBattleHubClientState.update(state.bpBalance)
        val frame = frameLayout()
        val layout = TowerPlayLayout.calculate(frame.content)
        drawContentFrame(graphics, frame, frame.helpButton.left)
        drawShell(graphics, layout, state)
        super.render(graphics, mouseX, mouseY, partialTick)
    }

    fun applyAccepted(requestId: UUID, state: TowerPlayViewState) {
        controller.apply(TowerPlayMutationResult.Accepted(requestId, state))
        rebuild()
    }

    fun applyRejected(result: TowerPlayMutationResult.Rejected) {
        controller.apply(result)
        rebuild()
    }

    private fun drawShell(graphics: GuiGraphics, layout: TowerPlayLayout, state: TowerPlayViewState) {
        MccGuiSurface.drawPanel(graphics, layout.partyPanel, MccGuiPalette.ACCENT_PRIMARY)
        MccGuiSurface.drawPanel(graphics, layout.mainPanel, MccGuiPalette.ACCENT_PRIMARY)
        MccGuiSurface.drawPanel(graphics, layout.detailsPanel, MccGuiPalette.ACCENT_SECONDARY, alternate = true)

        drawPartyHeading(graphics, layout, state)
        drawMainPanel(graphics, layout, state)
        drawDetails(graphics, layout, state)
    }

    private fun drawPartyHeading(graphics: GuiGraphics, layout: TowerPlayLayout, state: TowerPlayViewState) {
        val partyHeading = Component.translatable(
            "screen.more_cobblemon_contents.tower.section.party_count",
            state.selectedPokemonOrder.size,
            state.format.selectionSize,
        )
        graphics.drawString(
            font,
            partyHeading,
            layout.partyPanel.left + 6,
            layout.partyPanel.top + 6,
            MccGuiPalette.ACCENT_PRIMARY,
            false,
        )
    }

    private fun drawMainPanel(graphics: GuiGraphics, layout: TowerPlayLayout, state: TowerPlayViewState) {
        graphics.drawString(
            font,
            Component.translatable(
                "screen.more_cobblemon_contents.tower.progress",
                stageLabel(state),
                state.currentWinStreak,
                state.bestWinStreak,
                state.bpPerWin,
            ),
            layout.mainPanel.left + 7,
            layout.mainPanel.top + 6,
            MccGuiPalette.TEXT_PRIMARY,
            false,
        )
        layout.progressSegments(5).forEachIndexed { index, segment ->
            MccGuiSurface.drawProgressSegment(graphics, segment, index < state.winsIntoSet)
        }
        graphics.drawString(
            font,
            Component.translatable("screen.more_cobblemon_contents.tower.section.format"),
            layout.mainPanel.left + 7,
            layout.mainPanel.top + TowerPlayLayout.FORMAT_LABEL_OFFSET,
            MccGuiPalette.TEXT_SECONDARY,
            false,
        )
        graphics.drawString(
            font,
            Component.translatable("screen.more_cobblemon_contents.tower.section.mechanic"),
            layout.mainPanel.left + 7,
            layout.mainPanel.top + TowerPlayLayout.MECHANIC_LABEL_OFFSET,
            MccGuiPalette.TEXT_SECONDARY,
            false,
        )
    }

    private fun drawDetails(graphics: GuiGraphics, layout: TowerPlayLayout, state: TowerPlayViewState) {
        val phase = Component.translatable("screen.more_cobblemon_contents.tower.phase.${state.phase.name.lowercase()}")
        graphics.drawString(
            font,
            Component.translatable("screen.more_cobblemon_contents.tower.section.status").append(" · ").append(phase),
            layout.detailsPanel.left + 7,
            layout.detailsPanel.top + 6,
            phaseColor(state.phase),
            false,
        )
        val feedback = controller.fieldFeedbackKeys.firstOrNull()
            ?: controller.feedbackKey
            ?: state.errorKeys.firstOrNull()
        val summary = when {
            controller.isPending -> Component.translatable("screen.more_cobblemon_contents.tower.processing")
            feedback != null -> Component.translatable(feedback)
            else -> Component.translatable(
                "screen.more_cobblemon_contents.tower.selection_summary",
                state.selectedPokemonOrder.size,
                state.format.selectionSize,
                state.selectedMechanic?.let {
                    Component.translatable("screen.more_cobblemon_contents.tower.mechanic.${it.id}")
                } ?: Component.translatable("screen.more_cobblemon_contents.tower.mechanic.unselected"),
            )
        }
        val textLeft = layout.detailsPanel.left + 7
        val textWidth = (layout.detailsPanel.width - 14).coerceAtLeast(1)
        val actionTop = layout.actionButtons(1).first().top
        val availableSummaryLines = ((actionTop - 4 - (layout.detailsPanel.top + 20)) / TowerPlayLayout.SUMMARY_LINE_HEIGHT)
            .coerceIn(1, TowerPlayLayout.MAX_SUMMARY_LINES)
        val summaryLines = font.split(summary, textWidth).take(availableSummaryLines)
        summaryLines.forEachIndexed { index, line ->
            graphics.drawString(
                font,
                line,
                textLeft,
                layout.detailsPanel.top + 20 + index * TowerPlayLayout.SUMMARY_LINE_HEIGHT,
                if (feedback == null || controller.isPending) {
                    MccGuiPalette.TEXT_PRIMARY
                } else {
                    MccGuiPalette.ACCENT_DANGER
                },
                false,
            )
        }

        if (layout.mode == TowerPlayLayoutMode.WIDE) {
            val rulesTop = layout.detailsPanel.top + 28 + summaryLines.size * TowerPlayLayout.SUMMARY_LINE_HEIGHT
            val rules = listOf(
                "screen.more_cobblemon_contents.tower.rule.species",
                "screen.more_cobblemon_contents.tower.rule.items",
                "screen.more_cobblemon_contents.tower.rule.bag",
                "screen.more_cobblemon_contents.tower.rule.mechanic",
                "screen.more_cobblemon_contents.tower.rule.legendary_class",
            )
            rules.take(((actionTop - rulesTop - 2) / 12).coerceAtLeast(0)).forEachIndexed { index, key ->
                font.split(Component.translatable(key), textWidth).firstOrNull()?.let { line ->
                    graphics.drawString(
                        font,
                        line,
                        textLeft,
                        rulesTop + index * 12,
                        MccGuiPalette.TEXT_SECONDARY,
                        false,
                    )
                }
            }
        }
    }

    private fun buildWidgets() {
        val state = controller.state
        val frame = frameLayout()
        val layout = TowerPlayLayout.calculate(frame.content)
        addContentFrameWidgets(frame)
        val guideButton = MccStyledButton(
            frame.helpButton,
            Component.literal("?"),
            MccButtonTone.SECONDARY,
        ) {
            minecraft?.setScreen(TowerGuideScreen(this))
        }
        guideButton.setTooltip(
            Tooltip.create(Component.translatable(TowerGuideContent.BUTTON_TOOLTIP_KEY)),
        )
        guideButton.active = !controller.isPending
        addRenderableWidget(guideButton)
        val formatButtons = layout.formatButtons()
        addFormatButton(TowerBattleFormat.SINGLE, formatButtons[0])
        addFormatButton(TowerBattleFormat.DOUBLE, formatButtons[1])

        val mechanicButtons = layout.mechanicButtons(
            MajorBattleMechanic.entries.size + TowerLegendaryClassOption.entries.size,
        )
        MajorBattleMechanic.entries.forEachIndexed { index, mechanic ->
            addMechanicButton(mechanic, mechanicButtons[index])
        }
        TowerLegendaryClassOption.entries.forEachIndexed { index, option ->
            addLegendaryClassButton(option, mechanicButtons[MajorBattleMechanic.entries.size + index])
        }

        state.party.sortedBy(TowerPlayPartySlot::slot).forEachIndexed { index, pokemon ->
            val bounds = layout.partyCard(index)
            val selectionPosition = state.selectedPokemonOrder.indexOf(pokemon.pokemonId)
                .takeIf { it >= 0 }
                ?.plus(1)
            val pokemonName = speciesName(pokemon.speciesId)
            val heldItemName = itemName(pokemon.heldItemId)
            val button = TowerPartyCardButton(
                bounds = bounds,
                content = layout.partyCardContent(index),
                pokemon = pokemon,
                selectionPosition = selectionPosition,
                speciesName = pokemonName,
                heldItemName = heldItemName,
                portraits = portraits,
            ) {
                submit { controller.toggleSelection(pokemon.pokemonId) }
            }
            button.setTooltip(
                Tooltip.create(
                    Component.translatable(
                        "screen.more_cobblemon_contents.tower.party_entry.tooltip",
                        pokemonName,
                        pokemon.level,
                        pokemon.battleLevel,
                        heldItemName,
                    ),
                ),
            )
            button.active = state.phase == TowerPlayPhase.SELECTING && !controller.isPending
            addRenderableWidget(button)
        }

        when (state.phase) {
            TowerPlayPhase.SELECTING -> {
                val actions = layout.actionButtons(1)
                addActionButton(
                    Component.translatable("screen.more_cobblemon_contents.tower.lock"),
                    actions[0],
                    enabled = TowerPlayInteractionPolicy.canRequestLock(state, controller.isPending),
                ) { controller.lockTeam() }
            }

            TowerPlayPhase.TEAM_LOCKED -> {
                val actions = layout.actionButtons(2)
                addActionButton(
                    Component.translatable("screen.more_cobblemon_contents.tower.start"),
                    actions[0],
                ) { controller.start() }
                addActionButton(
                    Component.translatable("screen.more_cobblemon_contents.tower.change_team"),
                    actions[1],
                    tone = MccButtonTone.SECONDARY,
                ) { controller.abandon() }
            }

            TowerPlayPhase.ACTIVE -> {
                val actions = layout.actionButtons(2)
                addDisabledButton(
                    Component.translatable("screen.more_cobblemon_contents.tower.in_progress"),
                    actions[0],
                )
                addActionButton(
                    Component.translatable("screen.more_cobblemon_contents.tower.forfeit"),
                    actions[1],
                    tone = MccButtonTone.DANGER,
                ) {
                    confirmForfeit()
                    false
                }
            }
        }
    }

    private fun addFormatButton(format: TowerBattleFormat, bounds: TowerPlayRect) {
        val selected = controller.state.format == format
        val button = MccStyledButton(
            bounds,
            optionLabel("screen.more_cobblemon_contents.tower.format.${format.recordId}", selected),
            MccButtonTone.PRIMARY,
            selected,
        ) { submit { controller.changeFormat(format) } }
        button.active = controller.state.phase == TowerPlayPhase.SELECTING && !selected && !controller.isPending
        button.setTooltip(
            Tooltip.create(
                Component.translatable(
                    "screen.more_cobblemon_contents.tower.format.tooltip",
                    format.selectionSize,
                ),
            ),
        )
        addRenderableWidget(button)
    }

    private fun addMechanicButton(mechanic: MajorBattleMechanic, bounds: TowerPlayRect) {
        val selected = controller.state.selectedMechanic == mechanic
        val button = MccStyledButton(
            bounds,
            optionLabel("screen.more_cobblemon_contents.tower.mechanic.${mechanic.id}", selected),
            MccButtonTone.SECONDARY,
            selected,
        ) { submit { controller.changeMechanic(mechanic) } }
        button.active = controller.state.phase == TowerPlayPhase.SELECTING &&
            !controller.state.mechanicLocked && !selected && !controller.isPending
        button.setTooltip(
            Tooltip.create(
                Component.translatable(
                    "screen.more_cobblemon_contents.tower.mechanic.tooltip",
                    Component.translatable("screen.more_cobblemon_contents.tower.mechanic.${mechanic.id}"),
                ),
            ),
        )
        addRenderableWidget(button)
    }

    private fun addLegendaryClassButton(option: TowerLegendaryClassOption, bounds: TowerPlayRect) {
        val selected = controller.state.legendaryClassAllowed == option.allowed
        val labelKey = if (bounds.width >= 48) option.translationKey else option.compactTranslationKey
        val button = MccStyledButton(
            bounds,
            Component.translatable(labelKey),
            MccButtonTone.SECONDARY,
            selected,
        ) { submit { controller.changeLegendaryClassAllowed(option.allowed) } }
        button.active = controller.state.phase == TowerPlayPhase.SELECTING &&
            !controller.state.legendaryClassLocked && !selected && !controller.isPending
        button.setTooltip(
            Tooltip.create(
                Component.translatable("screen.more_cobblemon_contents.tower.legendary_class.tooltip"),
            ),
        )
        addRenderableWidget(button)
    }

    private fun addActionButton(
        label: Component,
        bounds: TowerPlayRect,
        enabled: Boolean = true,
        tone: MccButtonTone = MccButtonTone.PRIMARY,
        action: () -> Boolean,
    ) {
        val button = MccStyledButton(bounds, label, tone) { submit(action) }
        button.active = enabled && !controller.isPending
        addRenderableWidget(button)
    }

    private fun addDisabledButton(label: Component, bounds: TowerPlayRect) {
        val button = MccStyledButton(bounds, label) {}
        button.active = false
        addRenderableWidget(button)
    }

    private fun confirmForfeit() {
        minecraft?.setScreen(
            MccConfirmScreen(
                this,
                Component.translatable("screen.more_cobblemon_contents.tower.forfeit.confirm.title"),
                Component.translatable("screen.more_cobblemon_contents.tower.forfeit.confirm.message"),
            ) { submit(controller::abandon) },
        )
    }

    private fun submit(action: () -> Boolean) {
        if (action()) rebuild()
    }

    private fun rebuild() {
        clearWidgets()
        buildWidgets()
    }

    private fun stageLabel(state: TowerPlayViewState): Component =
        Component.translatable("screen.more_cobblemon_contents.tower.stage.${state.streakStage.serializedId}")

    private fun speciesName(speciesId: String): Component = Component.translatable(
        "cobblemon.species.${speciesId.substringAfter(':')}.name",
    )

    private fun itemName(itemId: String?): Component {
        if (itemId == null) {
            return Component.translatable("screen.more_cobblemon_contents.tower.held_item.none")
        }
        val namespace = itemId.substringBefore(':', "minecraft")
        val path = itemId.substringAfter(':')
        return Component.translatable("item.$namespace.$path")
    }

    private fun optionLabel(key: String, selected: Boolean): Component {
        val label = Component.translatable(key)
        return if (selected) {
            Component.translatable("screen.more_cobblemon_contents.tower.option.selected", label)
        } else {
            label
        }
    }

    private fun phaseColor(phase: TowerPlayPhase): Int = when (phase) {
        TowerPlayPhase.SELECTING -> MccGuiPalette.ACCENT_PRIMARY
        TowerPlayPhase.TEAM_LOCKED -> MccGuiPalette.ACCENT_GOOD
        TowerPlayPhase.ACTIVE -> MccGuiPalette.ACCENT_SECONDARY
    }
}

package jbro.cobblemon.morebattlecontent.client

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic
import jbro.cobblemon.morebattlecontent.internal.hub.BattleHubContent
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleFormat
import jbro.cobblemon.morebattlecontent.internal.tower.network.TowerPlayIntentPayload
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayMutationResult
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayInteractionPolicy
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayPartySlot
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayPhase
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayScreenController
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayViewState
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

internal class TowerPlayScreen(
    initialState: TowerPlayViewState,
) : MbcTabbedContentScreen(
    Component.translatable("screen.cobblemon_more_battle_content.tower.title"),
    BattleHubContent.BATTLE_TOWER,
) {
    private val portraits = MbcPokemonPortraitRenderer()
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
        MbcBattleHubClientState.update(state.bpBalance)
        val frame = frameLayout()
        val layout = TowerPlayLayout.calculate(frame.content)
        drawContentFrame(graphics, frame)
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
        MbcGuiSurface.drawPanel(graphics, layout.partyPanel, MbcGuiPalette.ACCENT_PRIMARY)
        MbcGuiSurface.drawPanel(graphics, layout.mainPanel, MbcGuiPalette.ACCENT_PRIMARY)
        MbcGuiSurface.drawPanel(graphics, layout.detailsPanel, MbcGuiPalette.ACCENT_SECONDARY, alternate = true)

        drawPartyHeading(graphics, layout, state)
        drawMainPanel(graphics, layout, state)
        drawDetails(graphics, layout, state)
    }

    private fun drawPartyHeading(graphics: GuiGraphics, layout: TowerPlayLayout, state: TowerPlayViewState) {
        val partyHeading = Component.translatable(
            "screen.cobblemon_more_battle_content.tower.section.party_count",
            state.selectedPokemonOrder.size,
            state.format.selectionSize,
        )
        graphics.drawString(
            font,
            partyHeading,
            layout.partyPanel.left + 6,
            layout.partyPanel.top + 6,
            MbcGuiPalette.ACCENT_PRIMARY,
            false,
        )
    }

    private fun drawMainPanel(graphics: GuiGraphics, layout: TowerPlayLayout, state: TowerPlayViewState) {
        graphics.drawString(
            font,
            Component.translatable(
                "screen.cobblemon_more_battle_content.tower.progress",
                rankLabel(state),
                progressValue(state),
                state.winsRequired,
            ),
            layout.mainPanel.left + 7,
            layout.mainPanel.top + 6,
            MbcGuiPalette.TEXT_PRIMARY,
            false,
        )
        val progress = progressValue(state)
        val filledSegments = if (state.winsRequired <= 0) {
            10
        } else {
            ((progress.coerceAtLeast(0) * 10 + state.winsRequired - 1) / state.winsRequired).coerceIn(0, 10)
        }
        layout.progressSegments(10).forEachIndexed { index, segment ->
            MbcGuiSurface.drawProgressSegment(graphics, segment, index < filledSegments)
        }
        graphics.drawString(
            font,
            Component.translatable("screen.cobblemon_more_battle_content.tower.section.format"),
            layout.mainPanel.left + 7,
            layout.mainPanel.top + TowerPlayLayout.FORMAT_LABEL_OFFSET,
            MbcGuiPalette.TEXT_SECONDARY,
            false,
        )
        graphics.drawString(
            font,
            Component.translatable("screen.cobblemon_more_battle_content.tower.section.mechanic"),
            layout.mainPanel.left + 7,
            layout.mainPanel.top + TowerPlayLayout.MECHANIC_LABEL_OFFSET,
            MbcGuiPalette.TEXT_SECONDARY,
            false,
        )
    }

    private fun drawDetails(graphics: GuiGraphics, layout: TowerPlayLayout, state: TowerPlayViewState) {
        val phase = Component.translatable("screen.cobblemon_more_battle_content.tower.phase.${state.phase.name.lowercase()}")
        graphics.drawString(
            font,
            Component.translatable("screen.cobblemon_more_battle_content.tower.section.status").append(" · ").append(phase),
            layout.detailsPanel.left + 7,
            layout.detailsPanel.top + 6,
            phaseColor(state.phase),
            false,
        )
        val feedback = controller.fieldFeedbackKeys.firstOrNull()
            ?: controller.feedbackKey
            ?: state.errorKeys.firstOrNull()
        val summary = when {
            controller.isPending -> Component.translatable("screen.cobblemon_more_battle_content.tower.processing")
            feedback != null -> Component.translatable(feedback)
            else -> Component.translatable(
                "screen.cobblemon_more_battle_content.tower.selection_summary",
                state.selectedPokemonOrder.size,
                state.format.selectionSize,
                state.selectedMechanic?.let {
                    Component.translatable("screen.cobblemon_more_battle_content.tower.mechanic.${it.id}")
                } ?: Component.translatable("screen.cobblemon_more_battle_content.tower.mechanic.unselected"),
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
                    MbcGuiPalette.TEXT_PRIMARY
                } else {
                    MbcGuiPalette.ACCENT_DANGER
                },
                false,
            )
        }

        if (layout.mode == TowerPlayLayoutMode.WIDE) {
            val rulesTop = layout.detailsPanel.top + 28 + summaryLines.size * TowerPlayLayout.SUMMARY_LINE_HEIGHT
            val rules = listOf(
                "screen.cobblemon_more_battle_content.tower.rule.species",
                "screen.cobblemon_more_battle_content.tower.rule.items",
                "screen.cobblemon_more_battle_content.tower.rule.bag",
                "screen.cobblemon_more_battle_content.tower.rule.mechanic",
            )
            rules.take(((actionTop - rulesTop - 2) / 12).coerceAtLeast(0)).forEachIndexed { index, key ->
                font.split(Component.translatable(key), textWidth).firstOrNull()?.let { line ->
                    graphics.drawString(
                        font,
                        line,
                        textLeft,
                        rulesTop + index * 12,
                        MbcGuiPalette.TEXT_SECONDARY,
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
        val formatButtons = layout.formatButtons()
        addFormatButton(TowerBattleFormat.SINGLE, formatButtons[0])
        addFormatButton(TowerBattleFormat.DOUBLE, formatButtons[1])

        val mechanicButtons = layout.mechanicButtons(MajorBattleMechanic.entries.size)
        MajorBattleMechanic.entries.forEachIndexed { index, mechanic ->
            addMechanicButton(mechanic, mechanicButtons[index])
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
                        "screen.cobblemon_more_battle_content.tower.party_entry.tooltip",
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
                    Component.translatable("screen.cobblemon_more_battle_content.tower.lock"),
                    actions[0],
                    enabled = TowerPlayInteractionPolicy.canRequestLock(state, controller.isPending),
                ) { controller.lockTeam() }
            }

            TowerPlayPhase.TEAM_LOCKED -> {
                val actions = layout.actionButtons(2)
                addActionButton(
                    Component.translatable("screen.cobblemon_more_battle_content.tower.start"),
                    actions[0],
                ) { controller.start() }
                addActionButton(
                    Component.translatable("screen.cobblemon_more_battle_content.tower.change_team"),
                    actions[1],
                    tone = MbcButtonTone.SECONDARY,
                ) { controller.abandon() }
            }

            TowerPlayPhase.ACTIVE -> {
                val actions = layout.actionButtons(2)
                addDisabledButton(
                    Component.translatable("screen.cobblemon_more_battle_content.tower.in_progress"),
                    actions[0],
                )
                addActionButton(
                    Component.translatable("screen.cobblemon_more_battle_content.tower.forfeit"),
                    actions[1],
                    tone = MbcButtonTone.DANGER,
                ) {
                    confirmForfeit()
                    false
                }
            }
        }
    }

    private fun addFormatButton(format: TowerBattleFormat, bounds: TowerPlayRect) {
        val selected = controller.state.format == format
        val button = MbcStyledButton(
            bounds,
            optionLabel("screen.cobblemon_more_battle_content.tower.format.${format.recordId}", selected),
            MbcButtonTone.PRIMARY,
            selected,
        ) { submit { controller.changeFormat(format) } }
        button.active = controller.state.phase == TowerPlayPhase.SELECTING && !selected && !controller.isPending
        button.setTooltip(
            Tooltip.create(
                Component.translatable(
                    "screen.cobblemon_more_battle_content.tower.format.tooltip",
                    format.selectionSize,
                ),
            ),
        )
        addRenderableWidget(button)
    }

    private fun addMechanicButton(mechanic: MajorBattleMechanic, bounds: TowerPlayRect) {
        val selected = controller.state.selectedMechanic == mechanic
        val button = MbcStyledButton(
            bounds,
            optionLabel("screen.cobblemon_more_battle_content.tower.mechanic.${mechanic.id}", selected),
            MbcButtonTone.SECONDARY,
            selected,
        ) { submit { controller.changeMechanic(mechanic) } }
        button.active = controller.state.phase == TowerPlayPhase.SELECTING &&
            !controller.state.mechanicLocked && !selected && !controller.isPending
        button.setTooltip(
            Tooltip.create(
                Component.translatable(
                    "screen.cobblemon_more_battle_content.tower.mechanic.tooltip",
                    Component.translatable("screen.cobblemon_more_battle_content.tower.mechanic.${mechanic.id}"),
                ),
            ),
        )
        addRenderableWidget(button)
    }

    private fun addActionButton(
        label: Component,
        bounds: TowerPlayRect,
        enabled: Boolean = true,
        tone: MbcButtonTone = MbcButtonTone.PRIMARY,
        action: () -> Boolean,
    ) {
        val button = MbcStyledButton(bounds, label, tone) { submit(action) }
        button.active = enabled && !controller.isPending
        addRenderableWidget(button)
    }

    private fun addDisabledButton(label: Component, bounds: TowerPlayRect) {
        val button = MbcStyledButton(bounds, label) {}
        button.active = false
        addRenderableWidget(button)
    }

    private fun confirmForfeit() {
        minecraft?.setScreen(
            MbcConfirmScreen(
                this,
                Component.translatable("screen.cobblemon_more_battle_content.tower.forfeit.confirm.title"),
                Component.translatable("screen.cobblemon_more_battle_content.tower.forfeit.confirm.message"),
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

    private fun rankLabel(state: TowerPlayViewState): Component =
        Component.translatable("screen.cobblemon_more_battle_content.tower.rank.${state.rank.serializedId}")

    private fun progressValue(state: TowerPlayViewState): Int =
        if (state.rank == jbro.cobblemon.morebattlecontent.internal.tower.TowerRank.MAX) {
            state.masterCycleWins
        } else {
            state.rankPoints
        }

    private fun speciesName(speciesId: String): Component = Component.translatable(
        "cobblemon.species.${speciesId.substringAfter(':')}.name",
    )

    private fun itemName(itemId: String?): Component {
        if (itemId == null) {
            return Component.translatable("screen.cobblemon_more_battle_content.tower.held_item.none")
        }
        val namespace = itemId.substringBefore(':', "minecraft")
        val path = itemId.substringAfter(':')
        return Component.translatable("item.$namespace.$path")
    }

    private fun optionLabel(key: String, selected: Boolean): Component {
        val label = Component.translatable(key)
        return if (selected) {
            Component.translatable("screen.cobblemon_more_battle_content.tower.option.selected", label)
        } else {
            label
        }
    }

    private fun phaseColor(phase: TowerPlayPhase): Int = when (phase) {
        TowerPlayPhase.SELECTING -> MbcGuiPalette.ACCENT_PRIMARY
        TowerPlayPhase.TEAM_LOCKED -> MbcGuiPalette.ACCENT_GOOD
        TowerPlayPhase.ACTIVE -> MbcGuiPalette.ACCENT_SECONDARY
    }
}

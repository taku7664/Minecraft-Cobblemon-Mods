package jbro.cobblemon.mcc.client

import java.util.UUID
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.hub.BattleHubContent
import jbro.cobblemon.mcc.internal.factory.FactoryBattleFormat
import jbro.cobblemon.mcc.internal.factory.FactoryLevelMode
import jbro.cobblemon.mcc.internal.factory.FactoryPlayError
import jbro.cobblemon.mcc.internal.factory.FactoryPlayPhase
import jbro.cobblemon.mcc.internal.factory.FactoryPlayView
import jbro.cobblemon.mcc.internal.factory.FactoryRentalSet
import jbro.cobblemon.mcc.internal.factory.FactorySwapOffer
import jbro.cobblemon.mcc.internal.factory.ui.feedbackId
import jbro.cobblemon.mcc.internal.factory.network.FactoryPlayIntentPayload
import jbro.cobblemon.mcc.internal.factory.ui.FactoryPlayScreenController
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

internal class FactoryPlayScreen(
    initialState: FactoryPlayView,
) : MccTabbedContentScreen(Component.translatable(key("title")), BattleHubContent.BATTLE_FACTORY) {
    private val portraits = MccPokemonPortraitRenderer()
    private val controller = FactoryPlayScreenController(
        initialState,
        sendIntent = { intent -> FactoryPlayClientNetworking.send(FactoryPlayIntentPayload(intent)) },
    )

    override fun init() = buildWidgets()

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val frame = frameLayout()
        val layout = FactoryPlayLayout.calculate(frame.content)
        val state = controller.state
        drawContentFrame(graphics, frame)
        MccGuiSurface.drawPanel(graphics, layout.summary, MccGuiPalette.ACCENT_PRIMARY)
        if (state.phase == FactoryPlayPhase.SWAP_DECISION) {
            MccGuiSurface.drawPanel(graphics, layout.swapTeamArea, MccGuiPalette.ACCENT_PRIMARY)
            MccGuiSurface.drawPanel(graphics, layout.swapOfferArea, MccGuiPalette.ACCENT_SECONDARY, alternate = true)
        } else {
            MccGuiSurface.drawPanel(graphics, layout.content, MccGuiPalette.ACCENT_PRIMARY)
        }
        MccGuiSurface.drawPanel(graphics, layout.footer, MccGuiPalette.BORDER_BRIGHT, alternate = true)
        drawSummary(graphics, layout, state)
        drawContentHeadings(graphics, layout, state)
        super.render(graphics, mouseX, mouseY, partialTick)
    }

    fun applyAccepted(requestId: UUID, state: FactoryPlayView) {
        controller.applyAccepted(requestId, state)
        rebuild()
    }

    fun applyRejected(requestId: UUID, error: FactoryPlayError) {
        controller.applyRejected(requestId, key("error.${error.feedbackId(controller.state.phase)}"))
        rebuild()
    }

    private fun drawSummary(graphics: GuiGraphics, layout: FactoryPlayLayout, state: FactoryPlayView) {
        val runSummary = if (state.format == null || state.levelMode == null) {
            Component.translatable(key("new_run"))
        } else {
            phaseSummary(state)
        }
        val phase = Component.translatable(key("phase.${state.phase.name.lowercase()}"))
        val summaryWidth = (layout.summary.width - font.width(phase) - 28).coerceAtLeast(1)
        graphics.drawString(
            font,
            font.split(runSummary, summaryWidth).first(),
            layout.summary.left + 7,
            layout.summary.top + 6,
            MccGuiPalette.TEXT_PRIMARY,
            false,
        )
        graphics.drawString(
            font,
            phase,
            layout.summary.right - 7 - font.width(phase),
            layout.summary.top + 6,
            phaseColor(state.phase),
            false,
        )
        val feedback = when {
            controller.isPending -> Component.translatable(key("processing"))
            controller.feedbackKey != null -> Component.translatable(controller.feedbackKey!!)
            else -> instruction(state)
        }
        val color = if (controller.feedbackKey == null) MccGuiPalette.TEXT_SECONDARY else MccGuiPalette.ACCENT_DANGER
        graphics.drawString(
            font,
            font.split(feedback, layout.summary.width - 14).first(),
            layout.summary.left + 7,
            layout.summary.top + 20,
            color,
            false,
        )
    }

    private fun drawContentHeadings(graphics: GuiGraphics, layout: FactoryPlayLayout, state: FactoryPlayView) {
        when (state.phase) {
            FactoryPlayPhase.AVAILABLE -> graphics.drawCenteredString(
                font,
                Component.translatable(key("section.rules")),
                layout.content.left + layout.content.width / 2,
                layout.content.top + 7,
                MccGuiPalette.ACCENT_SECONDARY,
            )
            FactoryPlayPhase.INITIAL_DRAFT, FactoryPlayPhase.ROUND_DRAFT -> drawSectionHeading(
                graphics,
                layout.content,
                Component.translatable(key("section.rentals"), controller.selectedSetIds.size, state.format?.selectionSize ?: 0),
                MccGuiPalette.ACCENT_PRIMARY,
            )
            FactoryPlayPhase.READY -> drawSectionHeading(
                graphics,
                layout.content,
                Component.translatable(key("section.order"), controller.battleOrderSetIds.size, state.teamSets.size),
                MccGuiPalette.ACCENT_GOOD,
            )
            FactoryPlayPhase.SWAP_DECISION -> {
                drawSectionHeading(graphics, layout.swapTeamArea, Component.translatable(key("section.current_team")), MccGuiPalette.ACCENT_PRIMARY)
                drawSectionHeading(graphics, layout.swapOfferArea, Component.translatable(key("section.opponent_offer")), MccGuiPalette.ACCENT_SECONDARY)
            }
            FactoryPlayPhase.IN_BATTLE -> drawCenteredState(graphics, layout, key("state.in_battle"), MccGuiPalette.ACCENT_SECONDARY)
            FactoryPlayPhase.COMPLETE -> drawCenteredState(graphics, layout, key("state.complete"), MccGuiPalette.ACCENT_GOOD)
        }
    }

    private fun drawSectionHeading(graphics: GuiGraphics, panel: TowerPlayRect, label: Component, color: Int) {
        graphics.drawString(font, label, panel.left + 7, panel.top + 6, color, false)
    }

    private fun drawCenteredState(graphics: GuiGraphics, layout: FactoryPlayLayout, translationKey: String, color: Int) {
        graphics.drawCenteredString(
            font,
            Component.translatable(translationKey),
            layout.content.left + layout.content.width / 2,
            layout.content.top + layout.content.height / 2 - 4,
            color,
        )
    }

    private fun buildWidgets() {
        val frame = frameLayout()
        val layout = FactoryPlayLayout.calculate(frame.content)
        addContentFrameWidgets(frame)
        when (controller.state.phase) {
            FactoryPlayPhase.AVAILABLE -> buildStartOptions(layout)
            FactoryPlayPhase.INITIAL_DRAFT, FactoryPlayPhase.ROUND_DRAFT -> buildDraft(layout)
            FactoryPlayPhase.READY -> buildReady(layout)
            FactoryPlayPhase.SWAP_DECISION -> buildSwap(layout)
            FactoryPlayPhase.COMPLETE -> buildComplete(layout)
            FactoryPlayPhase.IN_BATTLE -> buildInBattle(layout)
        }
    }

    private fun buildStartOptions(layout: FactoryPlayLayout) {
        FactoryBattleFormat.entries.forEachIndexed { index, format ->
            val selected = controller.chosenFormat == format
            addRenderableWidget(
                MccStyledButton(
                    layout.optionButtons(0)[index],
                    optionLabel("format.${format.name.lowercase()}", selected),
                    MccButtonTone.PRIMARY,
                    selected,
                ) { if (controller.chooseFormat(format)) rebuild() },
            )
        }
        FactoryLevelMode.entries.forEachIndexed { index, mode ->
            val selected = controller.chosenLevelMode == mode
            addRenderableWidget(
                MccStyledButton(
                    layout.optionButtons(1)[index],
                    optionLabel("level.${mode.id}", selected),
                    MccButtonTone.SECONDARY,
                    selected,
                ) { if (controller.chooseLevelMode(mode)) rebuild() },
            )
        }
        val actions = layout.actionButtons(1)
        addAction(actions[0], key("start"), MccButtonTone.PRIMARY) { controller.start() }
    }

    private fun buildDraft(layout: FactoryPlayLayout) {
        val state = controller.state
        layout.mainCards(state.draftSets.size).zip(state.draftSets).forEach { (bounds, set) ->
            val selected = set.setId in controller.selectedSetIds
            addRentalCard(bounds, set, selected, MccGuiPalette.ACCENT_PRIMARY) {
                if (controller.toggleRental(set.setId)) rebuild()
            }
        }
        val required = requireNotNull(state.format).selectionSize
        val actions = layout.actionButtons(2)
        addAction(actions[0], key("confirm_rentals"), MccButtonTone.PRIMARY, controller.selectedSetIds.size == required) {
            controller.confirmSelection()
        }
        addAbandon(actions[1])
    }

    private fun buildReady(layout: FactoryPlayLayout) {
        val state = controller.state
        layout.mainCards(state.teamSets.size).zip(state.teamSets).forEach { (bounds, set) ->
            val position = controller.battleOrderSetIds.indexOf(set.setId).takeIf { it >= 0 }?.plus(1)
            addRentalCard(bounds, set, position != null, MccGuiPalette.ACCENT_GOOD, position) {
                if (controller.toggleBattleOrder(set.setId)) rebuild()
            }
        }
        val actions = layout.actionButtons(3)
        addAction(actions[0], key("begin_battle"), MccButtonTone.PRIMARY, controller.battleOrderSetIds.size == state.teamSets.size) {
            controller.beginBattle()
        }
        addAction(actions[1], key("revise_selection"), MccButtonTone.SECONDARY, state.canReviseSelection) {
            controller.reviseSelection()
        }
        addAbandon(actions[2])
    }

    private fun buildSwap(layout: FactoryPlayLayout) {
        val state = controller.state
        val (teamCards, offerCards) = layout.swapCards(state.teamSets.size, state.swapOffers.size)
        teamCards.zip(state.teamSets).forEach { (bounds, set) ->
            val selected = controller.outgoingSetId == set.setId
            addRentalCard(bounds, set, selected, MccGuiPalette.ACCENT_PRIMARY) {
                if (controller.chooseOutgoing(set.setId)) rebuild()
            }
        }
        offerCards.zip(state.swapOffers).forEach { (bounds, offer) ->
            addOfferCard(bounds, offer, controller.incomingToken == offer.token)
        }
        val actions = layout.actionButtons(2)
        addAction(actions[0], key("keep_team"), MccButtonTone.PRIMARY) { controller.keepTeam() }
        addAction(
            actions[1],
            key("swap"),
            MccButtonTone.SECONDARY,
            controller.outgoingSetId != null && controller.incomingToken != null,
        ) { controller.swap() }
    }

    private fun buildComplete(layout: FactoryPlayLayout) {
        val actions = layout.actionButtons(1)
        addAction(actions[0], key("finish"), MccButtonTone.PRIMARY) { controller.abandon() }
    }

    private fun buildInBattle(layout: FactoryPlayLayout) {
        val actions = layout.actionButtons(1)
        addAbandon(actions[0])
    }

    private fun addRentalCard(
        bounds: TowerPlayRect,
        set: FactoryRentalSet,
        selected: Boolean,
        accent: Int,
        position: Int? = null,
        press: () -> Unit,
    ) {
        val name = if (position == null) rentalName(set) else Component.translatable(key("rental.position"), rentalName(set), position)
        val details = Component.translatable(
            key("card.details"),
            controller.state.levelMode?.battleLevel ?: 50,
            itemName(set.heldItemId),
        )
        val button = FactoryRentalCardButton(
            bounds = bounds,
            content = FactoryRentalCardContentLayout.calculate(bounds),
            identity = MccPokemonPortraitIdentity.factory(set.setId, set.speciesId, set.formId),
            primary = name,
            secondary = details,
            selected = selected,
            accent = accent,
            portraits = portraits,
            press = press,
        )
        button.active = !controller.isPending
        button.setTooltip(Tooltip.create(rentalTooltip(set)))
        addRenderableWidget(button)
    }

    private fun addOfferCard(bounds: TowerPlayRect, offer: FactorySwapOffer, selected: Boolean) {
        val name = speciesName(offer.speciesId, offer.formId)
        val details = Component.translatable(
            key("card.offer_details"),
            offer.revealedHeldItemId?.let(::itemName) ?: Component.translatable(key("unknown")),
        )
        val button = FactoryRentalCardButton(
            bounds = bounds,
            content = FactoryRentalCardContentLayout.calculate(bounds),
            identity = MccPokemonPortraitIdentity.offer(offer.token.toString(), offer.speciesId, offer.formId),
            primary = name,
            secondary = details,
            selected = selected,
            accent = MccGuiPalette.ACCENT_SECONDARY,
            portraits = portraits,
        ) {
            if (controller.chooseIncoming(offer.token)) rebuild()
        }
        button.active = !controller.isPending
        button.setTooltip(Tooltip.create(offerTooltip(offer)))
        addRenderableWidget(button)
    }

    private fun addAction(
        bounds: TowerPlayRect,
        translationKey: String,
        tone: MccButtonTone,
        enabled: Boolean = true,
        action: () -> Boolean,
    ) {
        val button = MccStyledButton(bounds, Component.translatable(translationKey), tone) {
            if (action()) rebuild()
        }
        button.active = enabled && !controller.isPending
        addRenderableWidget(button)
    }

    private fun addAbandon(bounds: TowerPlayRect) {
        val button = MccStyledButton(bounds, Component.translatable(key("abandon")), MccButtonTone.DANGER) { confirmAbandon() }
        button.active = !controller.isPending
        addRenderableWidget(button)
    }

    private fun confirmAbandon() {
        minecraft?.setScreen(
            MccConfirmScreen(
                this,
                Component.translatable(key("abandon.confirm.title")),
                Component.translatable(key("abandon.confirm.message")),
            ) { if (controller.abandon()) rebuild() },
        )
    }

    private fun phaseSummary(state: FactoryPlayView): Component = Component.translatable(
        key("summary"),
        Component.translatable(key("format.${state.format!!.name.lowercase()}")),
        Component.translatable(key("level.${state.levelMode!!.id}")),
        state.wins,
        state.rentAndTradeCount,
    )

    private fun instruction(state: FactoryPlayView): Component = Component.translatable(
        key(
            when (state.phase) {
                FactoryPlayPhase.AVAILABLE -> "instruction.options"
                FactoryPlayPhase.INITIAL_DRAFT, FactoryPlayPhase.ROUND_DRAFT -> "instruction.draft"
                FactoryPlayPhase.READY -> "instruction.ready"
                FactoryPlayPhase.IN_BATTLE -> "instruction.in_battle"
                FactoryPlayPhase.SWAP_DECISION -> "instruction.swap"
                FactoryPlayPhase.COMPLETE -> "instruction.complete"
            },
        ),
        state.format?.selectionSize ?: 0,
    )

    private fun optionLabel(path: String, selected: Boolean): Component =
        if (selected) Component.translatable(key("option.selected"), Component.translatable(key(path)))
        else Component.translatable(key(path))

    private fun rentalTooltip(set: FactoryRentalSet): Component = Component.translatable(
        key("rental.tooltip"),
        rentalName(set),
        controller.state.levelMode?.battleLevel ?: 50,
        natureName(set.natureId),
        abilityName(set.abilityId),
        itemName(set.heldItemId),
        moveList(set.moveIds),
    )

    private fun offerTooltip(offer: FactorySwapOffer): Component = Component.translatable(
        key("offer.tooltip"),
        speciesName(offer.speciesId, offer.formId),
        offer.revealedAbilityId?.let(::abilityName) ?: Component.translatable(key("unknown")),
        offer.revealedHeldItemId?.let(::itemName) ?: Component.translatable(key("unknown")),
        if (offer.revealedMoveIds.isEmpty()) Component.translatable(key("unknown")) else moveList(offer.revealedMoveIds),
    )

    private fun moveList(ids: Collection<String>): Component = Component.empty().also { result ->
        ids.forEachIndexed { index, id ->
            if (index > 0) result.append(Component.literal(" · "))
            result.append(moveName(id))
        }
    }

    private fun rentalName(set: FactoryRentalSet) = speciesName(set.speciesId, set.formId)
    private fun speciesName(id: String, formId: String? = null): Component {
        val base = Component.translatable("cobblemon.species.${path(id)}.name")
        if (formId == null || formId == "normal") return base
        return Component.translatable(key("form_name"), base, Component.translatable("cobblemon.ui.pokedex.info.form.${path(id)}-$formId"))
    }
    private fun moveName(id: String) = Component.translatable("cobblemon.move.${path(id)}")
    private fun abilityName(id: String) = Component.translatable("cobblemon.ability.${path(id)}")
    private fun natureName(id: String) = Component.translatable("cobblemon.nature.${path(id)}")
    private fun itemName(id: String?) = if (id == null) Component.translatable(key("held_item.none"))
        else Component.translatable("item.${id.substringBefore(':')}.${path(id)}")
    private fun path(id: String) = id.substringAfter(':')
    private fun phaseColor(phase: FactoryPlayPhase): Int = when (phase) {
        FactoryPlayPhase.AVAILABLE, FactoryPlayPhase.INITIAL_DRAFT, FactoryPlayPhase.ROUND_DRAFT -> MccGuiPalette.ACCENT_PRIMARY
        FactoryPlayPhase.READY, FactoryPlayPhase.COMPLETE -> MccGuiPalette.ACCENT_GOOD
        FactoryPlayPhase.IN_BATTLE, FactoryPlayPhase.SWAP_DECISION -> MccGuiPalette.ACCENT_SECONDARY
    }

    private fun rebuild() {
        clearWidgets()
        buildWidgets()
    }
}

private class FactoryRentalCardButton(
    bounds: TowerPlayRect,
    private val content: FactoryRentalCardContentLayout,
    private val identity: MccPokemonPortraitIdentity,
    private val primary: Component,
    private val secondary: Component,
    private val selected: Boolean,
    private val accent: Int,
    private val portraits: MccPokemonPortraitRenderer,
    private val press: () -> Unit,
) : AbstractButton(bounds.left, bounds.top, bounds.width, bounds.height, primary.copy().append(" ").append(secondary)) {
    override fun onPress() = press()

    override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        MccGuiSurface.drawButton(graphics, TowerPlayRect(x, y, width, height), active, isHoveredOrFocused, selected, accent)
        graphics.fill(
            content.portrait.left,
            content.portrait.top,
            content.portrait.right,
            content.portrait.bottom,
            MccGuiPalette.PANEL_ALT,
        )
        portraits.render(graphics, identity, content.portrait, partialTick, isHoveredOrFocused)
        val font = Minecraft.getInstance().font
        drawClipped(graphics, font, primary, y + 5, if (selected) accent else MccGuiPalette.TEXT_PRIMARY)
        if (height >= 24) {
            drawClipped(graphics, font, secondary, y + height - 11, MccGuiPalette.TEXT_SECONDARY)
        }
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)

    private fun drawClipped(graphics: GuiGraphics, font: Font, text: Component, top: Int, color: Int) {
        val line = font.split(text, (content.textRight - content.textLeft).coerceAtLeast(1)).firstOrNull() ?: return
        graphics.drawString(font, line, content.textLeft, top, color, false)
    }
}

private fun key(path: String) = "screen.${MoreCobblemonContents.MOD_ID}.factory.$path"

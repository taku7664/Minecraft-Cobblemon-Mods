package jbro.cobblemon.mcc.client

import java.util.UUID
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.client.hub.MccHubContentHost
import jbro.cobblemon.mcc.client.hub.MccHubKit
import jbro.cobblemon.mcc.client.hub.MccHubPortraitCards
import jbro.cobblemon.mcc.client.hub.MccHubScreen
import jbro.cobblemon.mcc.client.hub.MccHubTabContent
import jbro.cobblemon.mcc.client.hub.MccHubTabs
import jbro.cobblemon.mcc.client.hub.MccPokemonPortraits
import jbro.cobblemon.mcc.internal.factory.FactoryBattleFormat
import jbro.cobblemon.mcc.internal.factory.FactoryLevelMode
import jbro.cobblemon.mcc.internal.factory.FactoryPlayError
import jbro.cobblemon.mcc.internal.factory.FactoryPlayPhase
import jbro.cobblemon.mcc.internal.factory.FactoryPlayView
import jbro.cobblemon.mcc.internal.factory.FactoryRentalSet
import jbro.cobblemon.mcc.internal.factory.FactorySwapOffer
import jbro.cobblemon.mcc.internal.factory.network.FactoryPlayIntentPayload
import jbro.cobblemon.mcc.internal.factory.ui.FactoryPlayScreenController
import jbro.cobblemon.mcc.internal.factory.ui.feedbackId
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiLayout
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.client.CobblemonUiRenderContent
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component

/**
 * The Factory run the client knows, fed by the Factory network receivers. The server authors every state; the
 * controller keeps the local picks and the one request in flight.
 */
internal object FactoryHubClient {
    const val CONTENT: String = ManagedBattleContentIds.BATTLE_FACTORY

    var controller: FactoryPlayScreenController? = null
        private set
    private var openedByServer = false

    fun accept(requestId: UUID?, state: FactoryPlayView) {
        val current = controller
        if (requestId != null && current != null) {
            current.applyAccepted(requestId, state)
        } else {
            controller = FactoryPlayScreenController(state, sendIntent = { intent ->
                FactoryPlayClientNetworking.send(FactoryPlayIntentPayload(intent))
            })
        }
        when {
            // The battle takes the screen; the hub only gets out of its way.
            state.phase == FactoryPlayPhase.IN_BATTLE -> if (MccHubScreen.showing(CONTENT)) Minecraft.getInstance().setScreen(null)
            requestId != null || MccHubScreen.showing(CONTENT) -> MccHubScreen.refresh(CONTENT)
            else -> {
                openedByServer = true
                MccHubScreen.open(CONTENT)
            }
        }
    }

    fun reject(requestId: UUID, error: FactoryPlayError) {
        val current = controller ?: return
        current.applyRejected(requestId, "screen.more_cobblemon_contents.factory.error.${error.feedbackId(current.state.phase)}")
        MccHubScreen.refresh(CONTENT)
    }

    /** True once after the server opened the hub on Factory, whose fresh state the tab then uses. */
    fun takeOpenedByServer(): Boolean = openedByServer.also { openedByServer = false }
}

/** Battle Factory inside the MCC hub: rent a team, set its order, battle and trade between rounds. */
internal class FactoryHubTab : MccHubTabContent {
    override fun shown() {
        if (!FactoryHubClient.takeOpenedByServer()) MccHubTabs.requestContent(FactoryHubClient.CONTENT)
    }

    override fun build(host: MccHubContentHost, bounds: UiRect) {
        val controller = FactoryHubClient.controller
        if (controller == null) {
            MccHubKit.placeholder(host, bounds, factory("processing"))
            return
        }
        val state = controller.state
        val layout = FactoryHubLayout.calculate(bounds)
        MccHubKit.strip(host, layout.strip, runSummary(state), factory("phase.${state.phase.name.lowercase()}"))
        when (state.phase) {
            FactoryPlayPhase.AVAILABLE -> buildOptions(host, layout, controller)
            FactoryPlayPhase.INITIAL_DRAFT, FactoryPlayPhase.ROUND_DRAFT -> buildDraft(host, layout, controller)
            FactoryPlayPhase.READY -> buildReady(host, layout, controller)
            FactoryPlayPhase.SWAP_DECISION -> buildSwap(host, layout, controller)
            FactoryPlayPhase.IN_BATTLE -> buildState(host, layout, controller, "state.in_battle",
                start = listOf(abandon(controller, host)), end = emptyList())
            FactoryPlayPhase.COMPLETE -> buildState(host, layout, controller, "state.complete", start = emptyList(),
                end = listOf(action(controller, host, "finish", UiButtonVariant.PRIMARY, primary = true) { controller.abandon() }))
        }
    }

    private fun buildOptions(host: MccHubContentHost, layout: FactoryHubLayout, controller: FactoryPlayScreenController) {
        val enabled = !controller.isPending
        // Singles is the only format, so the level is the one choice before a run.
        val rows = listOf(
            MccHubKit.ChoiceRow(factory("section.level"),
                FactoryLevelMode.entries.map { MccHubKit.Choice(it.id, factory("level.${it.id}")) },
                controller.chosenLevelMode.id, enabled) { id ->
                if (controller.chooseLevelMode(FactoryLevelMode.entries.first { it.id == id })) host.rebuild()
            },
        )
        val steps = Component.empty()
        listOf("instruction.draft", "instruction.ready", "instruction.swap").forEachIndexed { index, key ->
            if (index > 0) steps.append(Component.literal("\n"))
            steps.append(Component.literal("${index + 1}. ")).append(factory(key, controller.chosenFormat.selectionSize))
        }
        // The card is only as tall as the settings, the instruction and the run's steps, centered in a taller body.
        val inner = MccHubKit.cardBody(layout.body)
        val settingsHeight = MccHubKit.choicesHeight(inner.width, inner.height - 20, rows)
        val stepsHeight = Minecraft.getInstance().font.split(steps, inner.width).size * 10
        val wanted = MccHubKit.CARD_CHROME_HEIGHT + settingsHeight + 5 + 10 + 8 + stepsHeight
        val fills = wanted >= layout.body.height
        val card = UiLayout.align(UiLayout.leaf("card"), height = wanted, fit = true).solve(layout.body)["card"]
        val body = MccHubKit.card(host, card, factory("section.rules"), MccHubKit.CardTone.FEATURE)
        val settingsBottom = MccHubKit.choices(host,
            if (fills) MccHubKit.settingsArea(body) else MccHubKit.lineAbove(body, settingsHeight).first, rows)
        // Spare room walks through the run ahead, reusing each phase's own instruction.
        val parts = UiLayout.column {
            space(5)
            fixed(10, "instruction")
            space(8)
            weight("steps")
        }.solve(MccHubKit.below(body, settingsBottom))
        instruction(host, parts["instruction"], controller)
        val stepsRect = parts["steps"]
        if (stepsRect.height >= 20) MccHubKit.text(host, stepsRect, steps)
        MccHubKit.footer(host, layout.footer, emptyList(),
            listOf(action(controller, host, "start", UiButtonVariant.PRIMARY, primary = true) { controller.start() }))
    }

    private fun buildDraft(host: MccHubContentHost, layout: FactoryHubLayout, controller: FactoryPlayScreenController) {
        val state = controller.state
        val required = state.format?.selectionSize ?: 0
        val body = MccHubKit.card(host, layout.body,
            factory("section.rentals", controller.selectedSetIds.size, required), MccHubKit.CardTone.FEATURE)
        val grid = instructionAbove(host, body, controller)
        MccHubPortraitCards.grid(grid, state.draftSets.size).zip(state.draftSets).forEach { (cell, set) ->
            host.add(rentalCard(cell, set, controller, set.setId in controller.selectedSetIds, null) {
                if (controller.toggleRental(set.setId)) host.rebuild()
            })
        }
        MccHubKit.footer(host, layout.footer, listOf(abandon(controller, host)),
            listOf(action(controller, host, "confirm_rentals", UiButtonVariant.PRIMARY, controller.selectedSetIds.size == required,
                primary = true) { controller.confirmSelection() }))
    }

    private fun buildReady(host: MccHubContentHost, layout: FactoryHubLayout, controller: FactoryPlayScreenController) {
        val state = controller.state
        val body = MccHubKit.card(host, layout.body,
            factory("section.order", controller.battleOrderSetIds.size, state.teamSets.size), MccHubKit.CardTone.FEATURE)
        val grid = instructionAbove(host, body, controller)
        MccHubPortraitCards.grid(grid, state.teamSets.size).zip(state.teamSets).forEach { (cell, set) ->
            val position = controller.battleOrderSetIds.indexOf(set.setId).takeIf { it >= 0 }?.plus(1)
            host.add(rentalCard(cell, set, controller, position != null, position) {
                if (controller.toggleBattleOrder(set.setId)) host.rebuild()
            })
        }
        MccHubKit.footer(host, layout.footer,
            listOf(abandon(controller, host), action(controller, host, "revise_selection", enabled = state.canReviseSelection) {
                controller.reviseSelection()
            }),
            listOf(action(controller, host, "begin_battle", UiButtonVariant.PRIMARY,
                controller.battleOrderSetIds.size == state.teamSets.size, primary = true) { controller.beginBattle() }))
    }

    private fun buildSwap(host: MccHubContentHost, layout: FactoryHubLayout, controller: FactoryPlayScreenController) {
        val state = controller.state
        val (teamArea, offerArea) = MccHubKit.columns(layout.body, 1, 1)
        val team = instructionAbove(host, MccHubKit.card(host, teamArea, factory("section.current_team"), MccHubKit.CardTone.FEATURE), controller)
        MccHubPortraitCards.grid(team, state.teamSets.size).zip(state.teamSets).forEach { (cell, set) ->
            host.add(rentalCard(cell, set, controller, controller.outgoingSetId == set.setId, null) {
                if (controller.chooseOutgoing(set.setId)) host.rebuild()
            })
        }
        val offers = MccHubKit.card(host, offerArea, factory("section.opponent_offer"))
        MccHubPortraitCards.grid(offers, state.swapOffers.size).zip(state.swapOffers).forEach { (cell, offer) ->
            host.add(offerCard(cell, offer, controller) { if (controller.chooseIncoming(offer.token)) host.rebuild() })
        }
        MccHubKit.footer(host, layout.footer, emptyList(), listOf(
            action(controller, host, "swap", enabled = controller.outgoingSetId != null && controller.incomingToken != null) { controller.swap() },
            action(controller, host, "keep_team", UiButtonVariant.PRIMARY, primary = true) { controller.keepTeam() },
        ))
    }

    private fun buildState(
        host: MccHubContentHost,
        layout: FactoryHubLayout,
        controller: FactoryPlayScreenController,
        stateKey: String,
        start: List<MccHubKit.Action>,
        end: List<MccHubKit.Action>,
    ) {
        val body = MccHubKit.card(host, layout.body, factory("phase.${controller.state.phase.name.lowercase()}"), MccHubKit.CardTone.FEATURE)
        MccHubKit.placeholder(host, body, feedback(controller) ?: factory(stateKey))
        MccHubKit.footer(host, layout.footer, start, end)
    }

    /** One line of instruction or feedback at the top of [body]; returns the room left below it. */
    private fun instructionAbove(host: MccHubContentHost, body: UiRect, controller: FactoryPlayScreenController): UiRect {
        val (line, rest) = MccHubKit.lineAbove(body)
        instruction(host, line, controller)
        return rest
    }

    private fun instruction(host: MccHubContentHost, rect: UiRect, controller: FactoryPlayScreenController) {
        if (rect.height < 9) return
        val feedback = feedback(controller)
        MccHubKit.text(host, rect, feedback ?: instructionText(controller.state)) { theme ->
            if (feedback != null && !controller.isPending) theme.colors.accentDanger else theme.colors.textDim
        }
    }

    private fun feedback(controller: FactoryPlayScreenController): Component? = when {
        controller.isPending -> factory("processing")
        controller.feedbackKey != null -> Component.translatable(controller.feedbackKey!!)
        else -> null
    }

    private fun action(
        controller: FactoryPlayScreenController,
        host: MccHubContentHost,
        key: String,
        variant: UiButtonVariant = UiButtonVariant.SECONDARY,
        enabled: Boolean = true,
        primary: Boolean = false,
        submit: () -> Boolean,
    ) = MccHubKit.Action(factory(key), variant, enabled && !controller.isPending, minWidth = if (primary) 96 else 0) {
        if (submit()) host.rebuild()
    }

    private fun abandon(controller: FactoryPlayScreenController, host: MccHubContentHost) =
        MccHubKit.Action(factory("abandon"), UiButtonVariant.DANGER, !controller.isPending) {
            MccHubKit.confirm(factory("abandon.confirm.title"), factory("abandon.confirm.message"), factory("abandon"),
                Component.translatable("gui.back")) { if (controller.abandon()) host.rebuild() }
        }

    private fun rentalCard(
        cell: MccHubPortraitCards.Cell,
        set: FactoryRentalSet,
        controller: FactoryPlayScreenController,
        marked: Boolean,
        position: Int?,
        press: () -> Unit,
    ): MccHubPortraitCards.Button {
        val level = controller.state.levelMode?.battleLevel ?: 50
        val name = speciesName(set.speciesId, set.formId)
        val details = factory("card.details", level, itemName(set.heldItemId))
        val card = MccHubPortraitCards.Button(cell, portrait("factory:${set.setId}", set.speciesId, set.formId),
            if (position == null) name else factory("rental.position", name, position), details, marked,
            Component.empty().append(name).append(" ").append(details), press)
        card.active = !controller.isPending
        card.setTooltip(Tooltip.create(factory("rental.tooltip", name, level, natureName(set.natureId), abilityName(set.abilityId),
            itemName(set.heldItemId), moveList(set.moveIds))))
        return card
    }

    private fun offerCard(
        cell: MccHubPortraitCards.Cell,
        offer: FactorySwapOffer,
        controller: FactoryPlayScreenController,
        press: () -> Unit,
    ): MccHubPortraitCards.Button {
        val unknown = factory("unknown")
        val name = speciesName(offer.speciesId, offer.formId)
        val details = factory("card.offer_details", offer.revealedHeldItemId?.let(::itemName) ?: unknown)
        val card = MccHubPortraitCards.Button(cell, portrait("factory-offer:${offer.token}", offer.speciesId, offer.formId),
            name, details, controller.incomingToken == offer.token, Component.empty().append(name).append(" ").append(details), press)
        card.active = !controller.isPending
        card.setTooltip(Tooltip.create(factory("offer.tooltip", name, offer.revealedAbilityId?.let(::abilityName) ?: unknown,
            offer.revealedHeldItemId?.let(::itemName) ?: unknown,
            if (offer.revealedMoveIds.isEmpty()) unknown else moveList(offer.revealedMoveIds))))
        return card
    }

    private fun runSummary(state: FactoryPlayView): Component {
        val format = state.format ?: return factory("new_run")
        val level = state.levelMode ?: return factory("new_run")
        return factory("summary", factory("format.${format.name.lowercase()}"), factory("level.${level.id}"), state.wins,
            state.rentAndTradeCount)
    }

    private fun instructionText(state: FactoryPlayView): Component = factory(
        when (state.phase) {
            FactoryPlayPhase.AVAILABLE -> "instruction.options"
            FactoryPlayPhase.INITIAL_DRAFT, FactoryPlayPhase.ROUND_DRAFT -> "instruction.draft"
            FactoryPlayPhase.READY -> "instruction.ready"
            FactoryPlayPhase.IN_BATTLE -> "instruction.in_battle"
            FactoryPlayPhase.SWAP_DECISION -> "instruction.swap"
            FactoryPlayPhase.COMPLETE -> "instruction.complete"
        },
        state.format?.selectionSize ?: 0,
    )

    private fun portrait(stateKey: String, speciesId: String, formId: String?): CobblemonUiRenderContent =
        MccPokemonPortraits.pokemon(stateKey, speciesId, formId) ?: CobblemonUiRenderContent.Empty
}

/** Battle Factory inside whatever rectangle of the hub content area it is given: a run strip, the phase's cards and a footer. */
internal data class FactoryHubLayout(val strip: UiRect, val body: UiRect, val footer: UiRect) {
    companion object {
        fun calculate(bounds: UiRect): FactoryHubLayout {
            val layout = MccHubKit.tabFrame(UiLayout.leaf("body")).solve(bounds)
            return FactoryHubLayout(layout["strip"], layout["body"], layout["footer"])
        }
    }
}

private fun factory(key: String, vararg args: Any): Component =
    Component.translatable("screen.more_cobblemon_contents.factory.$key", *args)

private fun path(id: String) = id.substringAfter(':')

private fun speciesName(id: String, formId: String? = null): Component {
    val base = Component.translatable("cobblemon.species.${path(id)}.name")
    if (formId == null || formId == "normal") return base
    return factory("form_name", base, Component.translatable("cobblemon.ui.pokedex.info.form.${path(id)}-$formId"))
}

private fun moveList(ids: Collection<String>): Component = Component.empty().also { result ->
    ids.forEachIndexed { index, id ->
        if (index > 0) result.append(Component.literal(" · "))
        result.append(Component.translatable("cobblemon.move.${path(id)}"))
    }
}

private fun abilityName(id: String) = Component.translatable("cobblemon.ability.${path(id)}")
private fun natureName(id: String) = Component.translatable("cobblemon.nature.${path(id)}")
private fun itemName(id: String?): Component = if (id == null) factory("held_item.none")
    else Component.translatable("item.${id.substringBefore(':')}.${path(id)}")

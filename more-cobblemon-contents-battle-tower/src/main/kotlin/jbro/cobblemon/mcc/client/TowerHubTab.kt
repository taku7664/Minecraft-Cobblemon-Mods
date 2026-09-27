package jbro.cobblemon.mcc.client

import java.util.UUID
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.api.rules.MajorBattleMechanic
import jbro.cobblemon.mcc.client.hub.MccHubContentHost
import jbro.cobblemon.mcc.client.hub.MccHubKit
import jbro.cobblemon.mcc.client.hub.MccHubPortraitCards
import jbro.cobblemon.mcc.client.hub.MccHubScreen
import jbro.cobblemon.mcc.client.hub.MccHubTabContent
import jbro.cobblemon.mcc.client.hub.MccHubTabs
import jbro.cobblemon.mcc.client.hub.MccPokemonPortraits
import jbro.cobblemon.mcc.internal.tower.TowerBattleFormat
import jbro.cobblemon.mcc.internal.tower.network.TowerPlayIntentPayload
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayInteractionPolicy
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayMutationResult
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayPartySlot
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayPhase
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayScreenController
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayViewState
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiRect
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component

internal enum class TowerLegendaryClassOption(val allowed: Boolean, val translationKey: String) {
    DISALLOWED(false, "screen.more_cobblemon_contents.tower.legendary_class.disallowed"),
    ALLOWED(true, "screen.more_cobblemon_contents.tower.legendary_class.allowed"),
}

/**
 * The Tower session the client knows, fed by the Tower network receivers. The server authors every state; the
 * controller only tracks the one request in flight.
 */
internal object TowerHubClient {
    const val CONTENT: String = ManagedBattleContentIds.BATTLE_TOWER

    var controller: TowerPlayScreenController? = null
        private set
    private var openedByServer = false

    /** A state the tab did not ask for (a terminal, a finished battle, an admin change) shows the Tower tab. */
    fun acceptOpened(state: TowerPlayViewState) {
        controller = TowerPlayScreenController(state) { intent -> TowerPlayClientNetworking.send(TowerPlayIntentPayload(intent)) }
        if (MccHubScreen.showing(CONTENT)) {
            MccHubScreen.refresh(CONTENT)
        } else {
            openedByServer = true
            MccHubScreen.open(CONTENT)
        }
    }

    fun acceptResult(result: TowerPlayMutationResult) {
        controller?.apply(result) ?: return
        MccHubScreen.refresh(CONTENT)
    }

    /** True once after the server opened the hub on Tower, whose fresh state the tab then uses. */
    fun takeOpenedByServer(): Boolean = openedByServer.also { openedByServer = false }
}

/** Battle Tower inside the MCC hub: pick six, choose the session rules, lock the team and climb. */
internal class TowerHubTab : MccHubTabContent {
    private var showingGuide = false
    private var guideOffset = 0
    private var scrollable: MccHubKit.Scrollable? = null

    override fun shown() {
        showingGuide = false
        if (!TowerHubClient.takeOpenedByServer()) MccHubTabs.requestContent(TowerHubClient.CONTENT)
    }

    override fun build(host: MccHubContentHost, bounds: UiRect) {
        scrollable = null
        val controller = TowerHubClient.controller
        if (controller == null) {
            MccHubKit.placeholder(host, bounds, tower("processing"))
            return
        }
        val state = controller.state
        val layout = TowerHubLayout.calculate(bounds)
        MccHubKit.strip(host, layout.strip,
            tower("progress", tower("stage.${state.streakStage.serializedId}"), state.currentWinStreak, state.bestWinStreak, state.bpPerWin),
            tower("phase.${state.phase.name.lowercase()}"), progress = state.winsIntoSet to 5)
        if (showingGuide) {
            buildGuide(host, layout)
            return
        }
        addParty(host, layout, controller)
        addSetup(host, layout, controller)
        addFooter(host, layout, controller)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollY: Double): Boolean =
        scrollable?.scroll(mouseX, mouseY, scrollY) == true

    private fun buildGuide(host: MccHubContentHost, layout: TowerHubLayout) {
        val card = UiRect(layout.party.x, layout.party.y, layout.setup.right - layout.party.x, layout.party.height)
        val body = MccHubKit.card(host, card, Component.translatable(TowerGuideContent.TITLE_KEY), MccHubKit.CardTone.FEATURE)
        val sections = TowerGuideContent.sections.map {
            MccHubKit.Section(Component.translatable(it.titleKey), Component.translatable(it.bodyKey))
        }
        scrollable = MccHubKit.document(host, body, sections, guideOffset) { guideOffset = it }
        MccHubKit.footer(host, layout.footer, listOf(MccHubKit.Action(Component.translatable(TowerGuideContent.CLOSE_KEY)) {
            showingGuide = false
            host.rebuild()
        }), emptyList())
    }

    private fun addParty(host: MccHubContentHost, layout: TowerHubLayout, controller: TowerPlayScreenController) {
        val state = controller.state
        val body = MccHubKit.card(host, layout.party,
            tower("section.party_count", state.selectedPokemonOrder.size, state.format.selectionSize), MccHubKit.CardTone.FEATURE)
        val party = state.party.sortedBy(TowerPlayPartySlot::slot)
        if (party.isEmpty()) {
            MccHubKit.placeholder(host, body, tower("error.party_size"))
            return
        }
        MccHubPortraitCards.grid(body, TowerHubLayout.PARTY_SIZE).forEachIndexed { index, cell ->
            val pokemon = party.getOrNull(index) ?: return@forEachIndexed
            val order = state.selectedPokemonOrder.indexOf(pokemon.pokemonId).takeIf { it >= 0 }?.plus(1)
            val speciesName = speciesName(pokemon.speciesId)
            val heldItem = itemName(pokemon.heldItemId)
            val button = MccHubPortraitCards.Button(cell, MccPokemonPortraits.party(pokemon.pokemonId, pokemon.speciesId, pokemon.formId),
                if (order == null) speciesName else tower("party_entry.order_name", order, speciesName),
                tower("party_entry.details", pokemon.battleLevel, heldItem), order != null,
                if (order == null) tower("party_entry.narration.available", speciesName, pokemon.battleLevel, heldItem)
                else tower("party_entry.narration.selected", order, speciesName, pokemon.battleLevel, heldItem)) {
                if (controller.toggleSelection(pokemon.pokemonId)) host.rebuild()
            }
            button.active = state.phase == TowerPlayPhase.SELECTING && !controller.isPending
            button.setTooltip(Tooltip.create(tower("party_entry.tooltip", speciesName, pokemon.level, pokemon.battleLevel, heldItem)))
            host.add(button)
        }
    }

    private fun addSetup(host: MccHubContentHost, layout: TowerHubLayout, controller: TowerPlayScreenController) {
        val state = controller.state
        val body = MccHubKit.card(host, layout.setup, tower("section.status"))
        val selecting = state.phase == TowerPlayPhase.SELECTING && !controller.isPending
        // Tall cards give each setting a title line of its own so its options can spread out below it.
        val rowHeight = if (body.height >= (MccHubKit.STACKED_CHOICE_HEIGHT + MccHubKit.GAP) * 3 + 20) {
            MccHubKit.STACKED_CHOICE_HEIGHT
        } else {
            MccHubKit.CONTROL_HEIGHT
        }
        var y = body.y
        fun row() = UiRect(body.x, y, body.width, rowHeight).also { y += rowHeight + MccHubKit.GAP }
        MccHubKit.choice(host, row(), tower("section.format"),
            TowerBattleFormat.entries.map { MccHubKit.Choice(it.recordId, tower("format.${it.recordId}")) },
            state.format.recordId, selecting, tower("format.tooltip", state.format.selectionSize)) { id ->
            if (controller.changeFormat(TowerBattleFormat.entries.first { it.recordId == id })) host.rebuild()
        }
        MccHubKit.choice(host, row(), tower("section.mechanic"),
            MajorBattleMechanic.entries.map { MccHubKit.Choice(it.id, tower("mechanic.${it.id}")) },
            state.selectedMechanic?.id, selecting && !state.mechanicLocked,
            state.selectedMechanic?.let { tower("mechanic.tooltip", tower("mechanic.${it.id}")) }) { id ->
            if (controller.changeMechanic(MajorBattleMechanic.entries.first { it.id == id })) host.rebuild()
        }
        MccHubKit.choice(host, row(), tower("section.legendary_class"),
            TowerLegendaryClassOption.entries.map { MccHubKit.Choice(it.name.lowercase(), Component.translatable(it.translationKey)) },
            TowerLegendaryClassOption.entries.first { it.allowed == state.legendaryClassAllowed }.name.lowercase(),
            selecting && !state.legendaryClassLocked, tower("legendary_class.tooltip")) { id ->
            val option = TowerLegendaryClassOption.entries.first { it.name.lowercase() == id }
            if (controller.changeLegendaryClassAllowed(option.allowed)) host.rebuild()
        }
        val feedback = controller.fieldFeedbackKeys.firstOrNull() ?: controller.feedbackKey ?: state.errorKeys.firstOrNull()
        val summary = when {
            controller.isPending -> tower("processing")
            feedback != null -> Component.translatable(feedback)
            else -> tower("selection_summary", state.selectedPokemonOrder.size, state.format.selectionSize,
                state.selectedMechanic?.let { tower("mechanic.${it.id}") } ?: tower("mechanic.unselected"))
        }
        val rest = UiRect(body.x, y, body.width, (body.bottom - y).coerceAtLeast(0))
        if (rest.height < 9) return
        val summaryLines = Minecraft.getInstance().font.split(summary, rest.width).size.coerceAtMost(rest.height / 10).coerceAtLeast(1)
        MccHubKit.text(host, UiRect(rest.x, rest.y, rest.width, summaryLines * 10), summary) { theme ->
            if (feedback != null && !controller.isPending) theme.colors.accentDanger else MccHubKit.panelText(theme)
        }
        // Whatever room is left lists the Tower rules, as far as it goes.
        val rules = UiRect(rest.x, rest.y + summaryLines * 10 + 5, rest.width, (rest.bottom - rest.y - summaryLines * 10 - 5).coerceAtLeast(0))
        if (rules.height >= 10) {
            val text = Component.empty()
            RULES.forEachIndexed { index, key ->
                if (index > 0) text.append(Component.literal("\n"))
                text.append(tower(key))
            }
            MccHubKit.text(host, rules, text) { theme -> theme.colors.textDim }
        }
    }

    private fun addFooter(host: MccHubContentHost, layout: TowerHubLayout, controller: TowerPlayScreenController) {
        val state = controller.state
        val pending = controller.isPending
        fun submit(action: () -> Boolean) { if (action()) host.rebuild() }
        val guide = MccHubKit.Action(Component.translatable(TowerGuideContent.OPEN_KEY), enabled = !pending,
            tooltip = Component.translatable(TowerGuideContent.BUTTON_TOOLTIP_KEY)) {
            showingGuide = true
            guideOffset = 0
            host.rebuild()
        }
        val end = when (state.phase) {
            TowerPlayPhase.SELECTING -> listOf(MccHubKit.Action(tower("lock"), UiButtonVariant.PRIMARY,
                TowerPlayInteractionPolicy.canRequestLock(state, pending), minWidth = 96) { submit(controller::lockTeam) })
            TowerPlayPhase.TEAM_LOCKED -> listOf(
                MccHubKit.Action(tower("change_team"), enabled = !pending) { submit(controller::abandon) },
                MccHubKit.Action(tower("start"), UiButtonVariant.PRIMARY, !pending, minWidth = 96) { submit(controller::start) },
            )
            TowerPlayPhase.ACTIVE -> listOf(
                MccHubKit.Action(tower("forfeit"), UiButtonVariant.DANGER, !pending) {
                    MccHubKit.confirm(tower("forfeit.confirm.title"), tower("forfeit.confirm.message"), tower("forfeit"),
                        Component.translatable("gui.back")) { submit(controller::abandon) }
                },
                MccHubKit.Action(tower("in_progress"), UiButtonVariant.PRIMARY, enabled = false, minWidth = 96) {},
            )
        }
        MccHubKit.footer(host, layout.footer, listOf(guide), end)
    }
}

private val RULES = listOf("rule.species", "rule.items", "rule.bag", "rule.mechanic", "rule.legendary_class")

private fun tower(key: String, vararg args: Any): Component =
    Component.translatable("screen.more_cobblemon_contents.tower.$key", *args)

private fun speciesName(speciesId: String): Component =
    Component.translatable("cobblemon.species.${speciesId.substringAfter(':')}.name")

private fun itemName(itemId: String?): Component {
    if (itemId == null) return tower("held_item.none")
    return Component.translatable("item.${itemId.substringBefore(':', "minecraft")}.${itemId.substringAfter(':')}")
}

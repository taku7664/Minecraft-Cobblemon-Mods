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
import jbro.cobblemon.mcc.internal.tower.TowerMode
import jbro.cobblemon.mcc.internal.tower.TOWER_BOSS_INTERVAL
import jbro.cobblemon.mcc.internal.tower.TOWER_NORMAL_CLEAR_WINS
import jbro.cobblemon.mcc.internal.tower.network.TowerPlayIntentPayload
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayInteractionPolicy
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayMutationResult
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayPartySlot
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayPhase
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayScreenController
import jbro.cobblemon.mcc.internal.tower.ui.TowerPlayViewState
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiLayout
import jbro.cobblemon.uikit.UiRect
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component

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
            when (state.mode) {
                TowerMode.NORMAL -> tower("progress.normal", tower("stage.${state.streakStage.serializedId}"), state.currentWinStreak,
                    TOWER_NORMAL_CLEAR_WINS, state.bestWinStreak, state.bpPerWin)
                TowerMode.ENDLESS -> tower("progress", tower("stage.${state.streakStage.serializedId}"), state.currentWinStreak,
                    state.bestWinStreak, state.bpPerWin)
            },
            tower("phase.${state.phase.name.lowercase()}"), progress = state.winsIntoSet to TOWER_BOSS_INTERVAL)
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
        // The guide takes both cards' room.
        val body = MccHubKit.card(host, layout.body, Component.translatable(TowerGuideContent.TITLE_KEY), MccHubKit.CardTone.FEATURE)
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
            // A legendary-class Pokemon cannot enter; its card says so without naming why.
            val blocked = pokemon.legendaryClass
            val button = MccHubPortraitCards.Button(cell, MccPokemonPortraits.party(pokemon.pokemonId, pokemon.speciesId, pokemon.formId),
                if (order == null) speciesName else tower("party_entry.order_name", order, speciesName),
                if (blocked) tower("party_entry.blocked") else tower("party_entry.details", pokemon.battleLevel, heldItem), order != null,
                when {
                    blocked -> tower("party_entry.narration.blocked", speciesName)
                    order == null -> tower("party_entry.narration.available", speciesName, pokemon.battleLevel, heldItem)
                    else -> tower("party_entry.narration.selected", order, speciesName, pokemon.battleLevel, heldItem)
                }) {
                if (controller.toggleSelection(pokemon.pokemonId)) host.rebuild()
            }
            button.blocked = blocked
            // A blocked Pokemon can still be taken off the team, never put on it.
            button.active = TowerPlayInteractionPolicy.picking(state) && !controller.isPending && (!blocked || order != null)
            val tooltip = tower("party_entry.tooltip", speciesName, pokemon.level, pokemon.battleLevel, heldItem)
            button.setTooltip(Tooltip.create(if (blocked) tooltip.copy().append("\n").append(tower("party_entry.blocked.tooltip")) else tooltip))
            host.add(button)
        }
    }

    private fun addSetup(host: MccHubContentHost, layout: TowerHubLayout, controller: TowerPlayScreenController) {
        val state = controller.state
        val body = MccHubKit.card(host, layout.setup, tower("section.status"))
        // The mode, the format and the rules are the run's: they change only while no team is registered.
        val rulesOpen = TowerPlayInteractionPolicy.rulesOpen(state) && !controller.isPending
        // The settings keep at least one summary line below them; a tall card lets them take title lines.
        val y = MccHubKit.choices(host, MccHubKit.settingsArea(body), listOf(
            // A locked Endless still shows, marked, so a challenger learns what clearing Normal opens.
            MccHubKit.ChoiceRow(tower("section.mode"),
                TowerMode.entries.map { mode ->
                    val locked = mode == TowerMode.ENDLESS && !state.endlessUnlocked
                    MccHubKit.Choice(mode.id, tower(if (locked) "mode.${mode.id}.locked" else "mode.${mode.id}"))
                },
                state.mode.id, rulesOpen, tower("mode.tooltip.${state.mode.id}")) { id ->
                if (controller.changeMode(TowerMode.entries.first { it.id == id })) host.rebuild()
            },
            MccHubKit.ChoiceRow(tower("section.format"),
                TowerBattleFormat.entries.map { MccHubKit.Choice(it.recordId, tower("format.${it.recordId}")) },
                state.format.recordId, rulesOpen, tower("format.tooltip", state.format.selectionSize)) { id ->
                if (controller.changeFormat(TowerBattleFormat.entries.first { it.recordId == id })) host.rebuild()
            },
            MccHubKit.ChoiceRow(tower("section.mechanic"),
                MajorBattleMechanic.entries.map { MccHubKit.Choice(it.id, tower("mechanic.${it.id}")) },
                state.selectedMechanic?.id, rulesOpen,
                state.selectedMechanic?.let { tower("mechanic.tooltip", tower("mechanic.${it.id}")) }) { id ->
                if (controller.changeMechanic(MajorBattleMechanic.entries.first { it.id == id })) host.rebuild()
            },
        )) + MccHubKit.GAP + 2
        val feedback = controller.fieldFeedbackKeys.firstOrNull() ?: controller.feedbackKey ?: state.errorKeys.firstOrNull()
        val summary = when {
            controller.isPending -> tower("processing")
            feedback != null -> Component.translatable(feedback)
            else -> tower("selection_summary", state.selectedPokemonOrder.size, state.format.selectionSize,
                state.selectedMechanic?.let { tower("mechanic.${it.id}") } ?: tower("mechanic.unselected"))
        }
        val rest = MccHubKit.below(body, y)
        if (rest.height < 9) return
        val summaryLines = Minecraft.getInstance().font.split(summary, rest.width).size.coerceAtMost(rest.height / 10).coerceAtLeast(1)
        // Whatever room the summary leaves lists the Tower rules, as far as it goes.
        val parts = UiLayout.column {
            fixed(summaryLines * 10, "summary")
            space(5)
            weight("rules")
        }.solve(rest)
        MccHubKit.text(host, parts["summary"], summary) { theme ->
            if (feedback != null && !controller.isPending) theme.colors.accentDanger else MccHubKit.panelText(theme)
        }
        val rules = parts["rules"]
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
        // Between battles a run under way can be given up; its streak ends there.
        val retire = listOfNotNull(MccHubKit.Action(tower("forfeit"), UiButtonVariant.DANGER, !pending) {
            MccHubKit.confirm(tower("forfeit.confirm.title"), tower("retire.confirm.message"), tower("forfeit"),
                Component.translatable("gui.back")) { submit(controller::retire) }
        }.takeIf { TowerPlayInteractionPolicy.canRetire(state) })
        val lock = MccHubKit.Action(tower("lock"), UiButtonVariant.PRIMARY,
            TowerPlayInteractionPolicy.canRequestLock(state, pending), minWidth = 96) { submit(controller::lockTeam) }
        val end = when (state.phase) {
            TowerPlayPhase.SELECTING, TowerPlayPhase.CHANGING_TEAM -> retire + lock
            TowerPlayPhase.TEAM_LOCKED -> retire + listOf(
                // Before the first battle the registration is let go; once the run is under way the six stay.
                MccHubKit.Action(tower("change_team"), enabled = !pending,
                    tooltip = tower(if (state.runStarted) "change_team.tooltip.run" else "change_team.tooltip.release")) {
                    submit(controller::changeTeam)
                },
                MccHubKit.Action(tower("start"), UiButtonVariant.PRIMARY, !pending, minWidth = 96) { submit(controller::start) },
            )
            TowerPlayPhase.ACTIVE -> listOf(
                MccHubKit.Action(tower("forfeit"), UiButtonVariant.DANGER, !pending) {
                    MccHubKit.confirm(tower("forfeit.confirm.title"), tower("forfeit.confirm.message"), tower("forfeit"),
                        Component.translatable("gui.back")) { submit(controller::forfeit) }
                },
                MccHubKit.Action(tower("in_progress"), UiButtonVariant.PRIMARY, enabled = false, minWidth = 96) {},
            )
        }
        MccHubKit.footer(host, layout.footer, listOf(guide), end)
    }
}

private val RULES = listOf("rule.species", "rule.items", "rule.bag", "rule.mechanic")

private fun tower(key: String, vararg args: Any): Component =
    Component.translatable("screen.more_cobblemon_contents.tower.$key", *args)

private fun speciesName(speciesId: String): Component =
    Component.translatable("cobblemon.species.${speciesId.substringAfter(':')}.name")

private fun itemName(itemId: String?): Component {
    if (itemId == null) return tower("held_item.none")
    return Component.translatable("item.${itemId.substringBefore(':', "minecraft")}.${itemId.substringAfter(':')}")
}

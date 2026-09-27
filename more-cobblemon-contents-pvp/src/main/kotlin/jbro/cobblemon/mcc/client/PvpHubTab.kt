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
import jbro.cobblemon.mcc.internal.pvp.PvpBattleFormat
import jbro.cobblemon.mcc.internal.pvp.PvpBattleMechanic
import jbro.cobblemon.mcc.internal.pvp.PvpRoomDefaults
import jbro.cobblemon.mcc.internal.pvp.PvpRoomPhase
import jbro.cobblemon.mcc.internal.pvp.PvpRoomSettings
import jbro.cobblemon.mcc.internal.pvp.PvpRoomSide
import jbro.cobblemon.mcc.internal.pvp.PvpRoomVisibility
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomClientView
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomIntent
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomIntentPayload
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomMemberView
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomSummaryView
import jbro.cobblemon.mcc.internal.pvp.network.PvpSelectionIntentPayload
import jbro.cobblemon.mcc.internal.pvp.ui.PvpRoomScreenController
import jbro.cobblemon.mcc.internal.pvp.ui.PvpSelectionOpponentSlot
import jbro.cobblemon.mcc.internal.pvp.ui.PvpSelectionScreenController
import jbro.cobblemon.mcc.internal.pvp.ui.PvpSelectionViewState
import jbro.cobblemon.uikit.CobblemonUiThemes
import jbro.cobblemon.uikit.UiButtonSpec
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiControlSize
import jbro.cobblemon.uikit.UiModelFraming
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.UiRenderSlotSpec
import jbro.cobblemon.uikit.UiThemeSnapshot
import jbro.cobblemon.uikit.UiWidthPolicy
import jbro.cobblemon.uikit.client.CobblemonUiButton
import jbro.cobblemon.uikit.client.CobblemonUiRenderContent
import jbro.cobblemon.uikit.client.CobblemonUiRenderSlot
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

/**
 * What the PvP tab shows and the sessions behind it, fed by the PvP network receivers. The server authors every
 * room and selection; the controllers only keep the request in flight.
 */
internal object PvpHubClient {
    const val CONTENT: String = ManagedBattleContentIds.PVP

    enum class View { LIST, ROOM, PICKER, SELECTION }

    var view = View.LIST
        private set
    var listFeedbackKey: String? = null
        private set
    var room: PvpRoomScreenController? = null
        private set
    var selection: PvpSelectionScreenController? = null
        private set
    var inviting = true
        private set
    var listPage = 0
    var pickerPage = 0
    var mechanicsOffset = 0
    private var openedByServer = false

    fun acceptRooms(rooms: List<PvpRoomSummaryView>) {
        PvpRoomClientState.lastRooms = rooms
        listFeedbackKey = null
        room = null
        if (view != View.SELECTION) view = View.LIST
        show()
    }

    fun acceptRoom(requestId: UUID?, state: PvpRoomClientView, reopen: Boolean) {
        PvpRoomClientState.lastRoom = state
        val requested = PvpRoomNavigationContract.shouldOpen(requestId, PvpRoomClientState.pendingOpenRequests, reopen)
        val current = room
        if (current != null && current.state.roomId == state.roomId && (view == View.ROOM || view == View.PICKER)) {
            current.applyState(requestId, state)
            MccHubScreen.refresh(CONTENT)
            return
        }
        if (!requested) return
        room = PvpRoomScreenController(state) { intent -> PvpPlayClientNetworking.send(PvpRoomIntentPayload(intent)) }
        if (view != View.SELECTION) view = View.ROOM
        show()
    }

    fun rejectRoom(requestId: UUID, messageKey: String) {
        PvpRoomClientState.pendingOpenRequests.remove(requestId)
        val current = room
        when {
            current != null && (view == View.ROOM || view == View.PICKER) && MccHubScreen.showing(CONTENT) -> {
                current.applyRejected(requestId, messageKey)
                MccHubScreen.refresh(CONTENT)
            }
            view == View.LIST && MccHubScreen.showing(CONTENT) -> {
                listFeedbackKey = messageKey
                MccHubScreen.refresh(CONTENT)
            }
            else -> Minecraft.getInstance().player?.displayClientMessage(Component.translatable(messageKey), false)
        }
    }

    fun acceptSelection(requestId: UUID?, state: PvpSelectionViewState) {
        val current = selection
        if (requestId != null && current != null && current.state.matchId == state.matchId) {
            current.applyAccepted(requestId, state)
            MccHubScreen.refresh(CONTENT)
            return
        }
        if (requestId != null) return
        selection = PvpSelectionScreenController(state, send = { intent -> PvpPlayClientNetworking.send(PvpSelectionIntentPayload(intent)) })
        view = View.SELECTION
        show()
    }

    fun rejectSelection(requestId: UUID, matchId: UUID, messageKey: String) {
        val current = selection ?: return
        if (current.state.matchId != matchId) return
        current.applyRejected(requestId, messageKey)
        MccHubScreen.refresh(CONTENT)
    }

    /** The team preview ended, into a battle or out of it; the hub gets out of the way either way. */
    fun closeSelection(matchId: UUID) {
        if (selection?.state?.matchId != matchId) return
        selection = null
        view = if (room != null) View.ROOM else View.LIST
        if (MccHubScreen.showing(CONTENT)) Minecraft.getInstance().setScreen(null)
    }

    /** The HUD's open control: the last room this client was in, refreshed through the join handshake when shown. */
    fun showLastRoom() {
        val last = PvpRoomClientState.lastRoom ?: return
        if (room?.state?.roomId != last.roomId) {
            room = PvpRoomScreenController(last) { intent -> PvpPlayClientNetworking.send(PvpRoomIntentPayload(intent)) }
        }
        if (view != View.SELECTION) view = View.ROOM
        MccHubScreen.open(CONTENT)
    }

    fun navigate(next: View, invite: Boolean = inviting) {
        view = next
        inviting = invite
        pickerPage = 0
        mechanicsOffset = 0
        MccHubScreen.refresh(CONTENT)
    }

    fun clear() {
        view = View.LIST
        listFeedbackKey = null
        room = null
        selection = null
        listPage = 0
        pickerPage = 0
        mechanicsOffset = 0
    }

    /** True once after the server opened the hub on PvP, whose fresh state the tab then uses. */
    fun takeOpenedByServer(): Boolean = openedByServer.also { openedByServer = false }

    private fun show() {
        if (MccHubScreen.showing(CONTENT)) {
            MccHubScreen.refresh(CONTENT)
        } else {
            openedByServer = true
            MccHubScreen.open(CONTENT)
        }
    }
}

/** PvP inside the MCC hub: browse rooms, sit down in one, and pick a team when the match forms. */
internal class PvpHubTab : MccHubTabContent {
    private var scrollable: MccHubKit.Scrollable? = null

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollY: Double): Boolean =
        scrollable?.scroll(mouseX, mouseY, scrollY) == true

    override fun shown() {
        if (PvpHubClient.takeOpenedByServer()) return
        when (PvpHubClient.view) {
            PvpHubClient.View.SELECTION -> Unit
            // A cached room is not proof of membership; the join handshake is idempotent and refreshes it.
            PvpHubClient.View.ROOM, PvpHubClient.View.PICKER ->
                PvpHubClient.room?.let { PvpPlayClientNetworking.openRoom(it.state.roomId) } ?: MccHubTabs.requestContent(PvpHubClient.CONTENT)
            PvpHubClient.View.LIST -> MccHubTabs.requestContent(PvpHubClient.CONTENT)
        }
    }

    override fun build(host: MccHubContentHost, bounds: UiRect) {
        scrollable = null
        val layout = PvpHubLayout.calculate(bounds)
        val selection = PvpHubClient.selection
        val room = PvpHubClient.room
        when {
            PvpHubClient.view == PvpHubClient.View.SELECTION && selection != null -> buildSelection(host, layout, selection)
            PvpHubClient.view == PvpHubClient.View.PICKER && room != null -> buildPicker(host, layout, room)
            PvpHubClient.view == PvpHubClient.View.ROOM && room != null -> buildRoom(host, layout, room)
            else -> buildList(host, layout)
        }
    }

    private fun buildList(host: MccHubContentHost, layout: PvpHubLayout) {
        val rooms = PvpRoomClientState.lastRooms
        MccHubKit.strip(host, layout.strip, room("title"), room("browser.summary", rooms.size))
        var body = MccHubKit.card(host, layout.body, room("browser.title"), MccHubKit.CardTone.FEATURE)
        PvpHubClient.listFeedbackKey?.let { key ->
            MccHubKit.text(host, UiRect(body.x, body.y, body.width, 10), Component.translatable(key)) { it.colors.accentDanger }
            body = UiRect(body.x, body.y + 13, body.width, (body.height - 13).coerceAtLeast(1))
        }
        MccHubKit.pagedList(host, body, rooms.map { summary ->
            MccHubKit.ListEntry(
                Component.literal(summary.host.name),
                Component.empty().append(room("format.${summary.settings.format.recordId}")).append(" · ")
                    .append(room("phase.${summary.phase.name.lowercase()}")).append(" · ")
                    .append(room("visibility.${summary.settings.visibility.name.lowercase()}")),
                room("spectators", summary.spectatorCount),
            ) { PvpPlayClientNetworking.openRoom(summary.roomId) }
        }, PvpHubClient.listPage, room("browser.empty")) { page ->
            PvpHubClient.listPage = page
            host.rebuild()
        }
        MccHubKit.footer(host, layout.footer,
            listOf(MccHubKit.Action(room("browser.refresh")) { sendRoom(PvpRoomIntent.Refresh(UUID.randomUUID())) }),
            listOf(
                MccHubKit.Action(room("browser.create_private")) { create(PvpRoomVisibility.PRIVATE) },
                MccHubKit.Action(room("browser.create_public"), UiButtonVariant.PRIMARY) { create(PvpRoomVisibility.PUBLIC) },
            ))
    }

    private fun buildRoom(host: MccHubContentHost, layout: PvpHubLayout, controller: PvpRoomScreenController) {
        val state = controller.state
        val me = Minecraft.getInstance().player?.uuid
        val isHost = state.hostId == me
        val lobby = state.phase == PvpRoomPhase.LOBBY
        val idle = !controller.isPending
        MccHubKit.strip(host, layout.strip,
            Component.empty().append(room("title")).append(" · ").append(room("phase.${state.phase.name.lowercase()}")),
            room("spectators", state.spectators.size))
        val (left, right, settings) = MccHubKit.columns(layout.body, 3, 3, 4)
        seat(host, left, PvpRoomSide.LEFT, state.leftPlayer, controller, me, lobby)
        seat(host, right, PvpRoomSide.RIGHT, state.rightPlayer, controller, me, lobby)

        val body = MccHubKit.card(host, settings, room("settings"))
        val editable = isHost && lobby && idle
        val settingsArea = UiRect(body.x, body.y, body.width, (body.height - 20).coerceAtLeast(MccHubKit.CONTROL_HEIGHT))
        val mechanics = state.settings.immutableEnabledMechanics
        fun toggleMechanic(mechanic: PvpBattleMechanic) {
            val next = LinkedHashSet(mechanics)
            if (!next.add(mechanic)) next.remove(mechanic)
            updateSettings(controller, state.settings.copy(enabledMechanics = next))
        }
        val rows = listOf(
            MccHubKit.ChoiceRow(room("group.visibility"),
                PvpRoomVisibility.entries.map { MccHubKit.Choice(it.name.lowercase(), room("visibility.${it.name.lowercase()}")) },
                state.settings.visibility.name.lowercase(), editable) { id ->
                updateSettings(controller, state.settings.copy(visibility = PvpRoomVisibility.entries.first { it.name.lowercase() == id }))
            },
            MccHubKit.ChoiceRow(room("group.format"),
                PvpBattleFormat.entries.map { MccHubKit.Choice(it.recordId, room("format.${it.recordId}")) },
                state.settings.format.recordId, editable) { id ->
                updateSettings(controller, state.settings.copy(format = PvpBattleFormat.entries.first { it.recordId == id }))
            },
            MccHubKit.ChoiceRow(room("group.mechanics"),
                PvpBattleMechanic.entries.map { MccHubKit.Choice(it.id, room("mechanic.${it.id}")) }, null, editable,
                selectedIds = mechanics.mapTo(HashSet()) { it.id }) { id ->
                toggleMechanic(PvpBattleMechanic.entries.first { it.id == id })
            },
        )
        if (MccHubKit.choiceMode(settingsArea.width, settingsArea.height, rows) == MccHubKit.ChoiceMode.COMPACT) {
            // A short card toggles visibility and format side by side, and lists one mechanic per row below them,
            // all with small controls so the four mechanics fit without scrolling where they can.
            val toggles = UiRect(body.x, body.y, body.width, MccHubKit.controlHeight(UiControlSize.SMALL))
            val visibility = state.settings.visibility
            val format = state.settings.format
            MccHubKit.buttonRow(host, toggles, listOf(
                MccHubKit.Action(room("visibility.${visibility.name.lowercase()}"), enabled = editable, tooltip = room("group.visibility")) {
                    updateSettings(controller, state.settings.copy(visibility = PvpRoomVisibility.entries[(visibility.ordinal + 1) % PvpRoomVisibility.entries.size]))
                },
                MccHubKit.Action(room("format.${format.recordId}"), enabled = editable, tooltip = room("group.format")) {
                    updateSettings(controller, state.settings.copy(format = PvpBattleFormat.entries[(format.ordinal + 1) % PvpBattleFormat.entries.size]))
                },
            ), UiControlSize.SMALL)
            var listBottom = body.bottom
            controller.feedbackKey?.let { key ->
                MccHubKit.text(host, UiRect(body.x, body.bottom - 10, body.width, 10), Component.translatable(key)) { it.colors.accentDanger }
                listBottom -= 13
            }
            scrollable = MccHubKit.scrollList(host,
                UiRect(body.x, toggles.bottom + MccHubKit.GAP, body.width, (listBottom - toggles.bottom - MccHubKit.GAP).coerceAtLeast(1)),
                PvpBattleMechanic.entries.map { mechanic ->
                    val enabled = mechanic in mechanics
                    MccHubKit.ListEntry(room("mechanic.${mechanic.id}"), trailing = if (enabled) Component.literal("✓") else null,
                        selected = enabled, enabled = editable, tooltip = room("group.mechanics")) { toggleMechanic(mechanic) }
                }, PvpHubClient.mechanicsOffset, UiControlSize.SMALL) { PvpHubClient.mechanicsOffset = it }
        } else {
            var y = MccHubKit.choices(host, settingsArea, rows) + MccHubKit.GAP + 2
            controller.feedbackKey?.let { key ->
                MccHubKit.text(host, UiRect(body.x, y, body.width, 10), Component.translatable(key)) { it.colors.accentDanger }
                y += 13
            }
            val spectators = UiRect(body.x, y, body.width, (body.bottom - y).coerceAtLeast(0))
            if (spectators.height >= 10 && state.spectators.isNotEmpty()) host.add(Faces(spectators, state.spectators))
        }

        val members = (listOfNotNull(state.leftPlayer, state.rightPlayer) + state.spectators).distinctBy(PvpRoomMemberView::playerId)
        val manageable = isHost && lobby && idle && (state.inviteCandidates.isNotEmpty() || members.any { it.playerId != state.hostId })
        MccHubKit.footer(host, layout.footer,
            listOf(
                MccHubKit.Action(room("leave"), UiButtonVariant.DANGER, idle) { controller.submit(PvpRoomIntent.Leave(UUID.randomUUID(), state.roomId)) },
                MccHubKit.Action(room("back_to_list")) {
                    sendRoom(PvpRoomIntent.Refresh(UUID.randomUUID()))
                    PvpHubClient.navigate(PvpHubClient.View.LIST)
                },
            ),
            listOf(
                MccHubKit.Action(room("invite_manage"), enabled = manageable) {
                    PvpHubClient.navigate(PvpHubClient.View.PICKER, invite = state.inviteCandidates.isNotEmpty())
                },
                MccHubKit.Action(room("start"), UiButtonVariant.PRIMARY,
                    isHost && lobby && idle && state.leftPlayer != null && state.rightPlayer != null) {
                    controller.submit(PvpRoomIntent.Start(UUID.randomUUID(), state.roomId))
                },
            ))
    }

    /** A seat: whoever sits there as a full-body UI kit model, their name, and the control that claims or leaves it. */
    private fun seat(
        host: MccHubContentHost,
        rect: UiRect,
        side: PvpRoomSide,
        occupant: PvpRoomMemberView?,
        controller: PvpRoomScreenController,
        me: UUID?,
        lobby: Boolean,
    ) {
        val state = controller.state
        val body = MccHubKit.card(host, rect, room("side.${side.name.lowercase()}"),
            if (side == PvpRoomSide.LEFT) MccHubKit.CardTone.FEATURE else MccHubKit.CardTone.INFO)
        val buttonTop = body.bottom - MccHubKit.CONTROL_HEIGHT
        val model = UiRect(body.x, body.y, body.width, (buttonTop - 14 - body.y).coerceAtLeast(8))
        val profile = occupant?.let { Minecraft.getInstance().connection?.getPlayerInfo(it.playerId)?.profile }
        if (occupant == null || profile == null) {
            MccHubKit.placeholder(host, model, if (occupant == null) Component.literal("+") else Component.literal(occupant.name))
        } else {
            host.add(CobblemonUiRenderSlot.create(model.x, model.y, model.width, model.height, UiRenderSlotSpec(Component.literal(occupant.name)),
                CobblemonUiRenderContent.PlayerProfile(profile, UiModelFraming.FULL_BODY)))
        }
        if (occupant != null) {
            val name = Component.literal(occupant.name).append(if (occupant.playerId == state.hostId) room("host_suffix") else Component.empty())
            host.add(CenteredLine(UiRect(body.x, buttonTop - 11, body.width, 10), name))
        }
        val mine = occupant != null && occupant.playerId == me
        val label = when {
            occupant == null -> room("join_side")
            mine -> room("observe")
            else -> Component.literal(occupant.name)
        }
        val button = CobblemonUiButton.create(body.x, buttonTop, body.width,
            UiButtonSpec(MccHubKit.fitted(label, body.width - 12), variant = if (mine) UiButtonVariant.SECONDARY else UiButtonVariant.PRIMARY,
                size = UiControlSize.MEDIUM, width = UiWidthPolicy.Fixed(body.width))) {
            if (mine) controller.submit(PvpRoomIntent.Observe(UUID.randomUUID(), state.roomId))
            else controller.submit(PvpRoomIntent.ClaimSeat(UUID.randomUUID(), state.roomId, side))
            host.rebuild()
        }
        button.active = lobby && !controller.isPending && (occupant == null || mine)
        if (mine) button.setTooltip(Tooltip.create(room("your_side")))
        host.add(button)
    }

    private fun buildPicker(host: MccHubContentHost, layout: PvpHubLayout, controller: PvpRoomScreenController) {
        val state = controller.state
        val members = (listOfNotNull(state.leftPlayer, state.rightPlayer) + state.spectators)
            .distinctBy(PvpRoomMemberView::playerId).filter { it.playerId != state.hostId }
        val inviting = PvpHubClient.inviting
        MccHubKit.strip(host, layout.strip, room("title"), room("spectators", state.spectators.size))
        val body = MccHubKit.card(host, layout.body, room(if (inviting) "picker.invite" else "picker.transfer"), MccHubKit.CardTone.FEATURE)
        val listTop = MccHubKit.choices(host, UiRect(body.x, body.y, body.width, MccHubKit.CONTROL_HEIGHT), listOf(
            MccHubKit.ChoiceRow(room("invite_manage"), listOf(
                MccHubKit.Choice("invite", room("invite_manage")),
                MccHubKit.Choice("transfer", room("transfer_manage")),
            ), if (inviting) "invite" else "transfer", !controller.isPending) { id ->
                PvpHubClient.navigate(PvpHubClient.View.PICKER, invite = id == "invite")
            },
        )) + MccHubKit.GAP + 3
        val candidates = if (inviting) state.inviteCandidates else members
        MccHubKit.pagedList(host, UiRect(body.x, listTop, body.width, (body.bottom - listTop).coerceAtLeast(1)), candidates.map { member ->
            MccHubKit.ListEntry(Component.literal(member.name), enabled = !controller.isPending) {
                val intent = if (inviting) PvpRoomIntent.Invite(UUID.randomUUID(), state.roomId, member.playerId)
                    else PvpRoomIntent.TransferHost(UUID.randomUUID(), state.roomId, member.playerId)
                controller.submit(intent)
                PvpHubClient.navigate(PvpHubClient.View.ROOM)
            }
        }, PvpHubClient.pickerPage, room("picker.empty")) { page ->
            PvpHubClient.pickerPage = page
            host.rebuild()
        }
        MccHubKit.footer(host, layout.footer,
            listOf(MccHubKit.Action(Component.translatable("gui.back")) { PvpHubClient.navigate(PvpHubClient.View.ROOM) }), emptyList())
    }

    private fun buildSelection(host: MccHubContentHost, layout: PvpHubLayout, controller: PvpSelectionScreenController) {
        val state = controller.state
        MccHubKit.strip(host, layout.strip,
            Component.empty().append(pvp("title")).append(" · ").append(pvp("format.${state.format.recordId}")))
        host.add(LiveLine(UiRect(layout.strip.x, layout.strip.y, layout.strip.width - 6, layout.strip.height), alignEnd = true,
            color = { it.colors.textPrimary }) { pvp("time_remaining", remainingSeconds(state)) })
        val (left, right) = MccHubKit.columns(layout.body, 1, 1)
        if (state.spectatorMode) {
            publicTeam(host, left, state.leftPlayerName, state.spectatorLeftParty, state, 0, MccHubKit.CardTone.FEATURE)
            publicTeam(host, right, state.rightPlayerName, state.spectatorRightParty, state, TEAM_SIZE, MccHubKit.CardTone.INFO)
            MccHubKit.footer(host, layout.footer,
                listOf(MccHubKit.Action(pvp("return")) { PvpPlayClientNetworking.exitLoungeSpectator() }), emptyList())
            return
        }
        val ownName = (if (state.playerOnLeft) state.leftPlayerName else state.rightPlayerName)
            .ifBlank { Minecraft.getInstance().player?.scoreboardName.orEmpty() }
        val opponentName = (if (state.playerOnLeft) state.rightPlayerName else state.leftPlayerName).ifBlank { state.opponentName }
        val ownRect = if (state.playerOnLeft) left else right
        val opponentRect = if (state.playerOnLeft) right else left
        val own = MccHubKit.card(host, ownRect, Component.literal(ownName), MccHubKit.CardTone.FEATURE)
        host.add(LiveLine(UiRect(own.x, own.y, own.width, 10), color = { theme ->
            if (controller.feedbackKey != null && !controller.isPending) theme.colors.accentDanger else theme.colors.textDim
        }) { selectionStatus(controller) })
        val grid = UiRect(own.x, own.y + 13, own.width, (own.height - 13).coerceAtLeast(1))
        val order = controller.selectedPokemonIds.toList()
        val party = state.ownParty.take(TEAM_SIZE)
        MccHubPortraitCards.grid(grid, party.size).zip(party).forEach { (cell, slot) ->
            val position = order.indexOf(slot.pokemonId).takeIf { it >= 0 }?.plus(1)
            val name = speciesName(slot.speciesId)
            val card = MccHubPortraitCards.Button(cell, MccPokemonPortraits.party(slot.pokemonId, slot.speciesId, slot.formId),
                if (position == null) name else Component.literal("($position) ").append(name),
                Component.literal("Lv. ${slot.battleLevel}"), position != null, name) {
                if (controller.toggle(slot.pokemonId)) host.rebuild()
            }
            card.active = !controller.isPending && !state.waitingForOpponent
            card.setTooltip(Tooltip.create(pvp("party_entry.preview_tooltip", name, slot.originalLevel, slot.battleLevel)))
            host.add(card)
        }
        publicTeam(host, opponentRect, opponentName, state.opponentParty, state, 0, MccHubKit.CardTone.INFO, withCard = true)

        val primary = when {
            state.waitingForOpponent -> MccHubKit.Action(pvp("unready"), enabled = !controller.isPending) {
                if (controller.unready()) host.rebuild()
            }
            state.battleStartRetryAvailable -> MccHubKit.Action(pvp("retry"), UiButtonVariant.PRIMARY, !controller.isPending, minWidth = 96) {
                if (controller.retry()) host.rebuild()
            }
            else -> MccHubKit.Action(pvp("confirm_selection"), UiButtonVariant.PRIMARY,
                !controller.isPending && controller.selectedPokemonIds.size == state.format.selectionSize, minWidth = 96) {
                if (controller.submit()) host.rebuild()
            }
        }
        MccHubKit.footer(host, layout.footer, listOf(MccHubKit.Action(pvp("cancel"), UiButtonVariant.DANGER, !controller.isPending) {
            MccHubKit.confirm(pvp("cancel.confirm.title"), pvp("cancel.confirm.message"), pvp("cancel"), Component.translatable("gui.back")) {
                if (controller.cancel()) MccHubScreen.refresh(PvpHubClient.CONTENT)
            }
        }), listOf(primary))
    }

    /** A team whose species alone are public: an opponent's preview, or either side for a spectator. */
    private fun publicTeam(
        host: MccHubContentHost,
        rect: UiRect,
        name: String,
        party: List<PvpSelectionOpponentSlot>,
        state: PvpSelectionViewState,
        keyOffset: Int,
        tone: MccHubKit.CardTone,
        withCard: Boolean = true,
    ) {
        val body = if (withCard) MccHubKit.card(host, rect, Component.literal(name), tone) else rect
        val team = party.take(TEAM_SIZE)
        MccHubPortraitCards.grid(body, team.size).zip(team).forEachIndexed { index, (cell, slot) ->
            val portrait = MccPokemonPortraits.pokemon("pvp-opponent:${state.matchId}:${keyOffset + index}", slot.speciesId, slot.formId,
                animate = false) ?: CobblemonUiRenderContent.Empty
            val species = speciesName(slot.speciesId)
            val card = MccHubPortraitCards.Button(cell, portrait, species, Component.empty(), false, species) {}
            card.active = false
            host.add(card)
        }
    }

    private fun selectionStatus(controller: PvpSelectionScreenController): Component {
        val state = controller.state
        return when {
            controller.isPending -> pvp("processing")
            controller.feedbackKey != null -> Component.translatable(controller.feedbackKey!!)
            state.battleStartRetryAvailable -> pvp("error.battle_unavailable")
            state.waitingForOpponent -> pvp("waiting")
            else -> pvp("selection_summary", controller.selectedPokemonIds.size, state.format.selectionSize, remainingSeconds(state))
        }
    }

    private fun updateSettings(controller: PvpRoomScreenController, settings: PvpRoomSettings) {
        controller.submit(PvpRoomIntent.UpdateSettings(UUID.randomUUID(), controller.state.roomId, settings))
        MccHubScreen.refresh(PvpHubClient.CONTENT)
    }

    private fun create(visibility: PvpRoomVisibility) = sendRoom(PvpRoomIntent.Create(UUID.randomUUID(),
        PvpRoomSettings(visibility, PvpBattleFormat.SINGLE, PvpRoomDefaults.ENABLED_MECHANICS)))

    private fun sendRoom(intent: PvpRoomIntent) = PvpPlayClientNetworking.send(PvpRoomIntentPayload(intent))

    /** Spectators as faces with names, drawn by the UI kit, in as many columns as the room allows. */
    private class Faces(private val rect: UiRect, private val members: List<PvpRoomMemberView>) :
        AbstractWidget(rect.x, rect.y, rect.width, rect.height, Component.empty()) {
        init { active = false }
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            val font = Minecraft.getInstance().font
            val connection = Minecraft.getInstance().connection
            val grid = PvpSpectatorGridLayout.calculate(MccRect(rect.x, rect.y, rect.width, rect.height), members.map { font.width(it.name) })
            grid.slots.forEach { slot ->
                val member = members[slot.index]
                connection?.getPlayerInfo(member.playerId)?.profile?.let { profile ->
                    CobblemonUiRenderSlot.drawContent(graphics, UiRect(slot.face.left, slot.face.top, slot.face.width, slot.face.height),
                        CobblemonUiRenderContent.PlayerFace(profile), partialTick)
                }
                graphics.drawString(font, font.plainSubstrByWidth(member.name, slot.nameWidth), slot.nameLeft, slot.bounds.top + 2,
                    MccHubKit.panelText(theme), false)
            }
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    private class CenteredLine(private val rect: UiRect, private val text: Component) :
        AbstractWidget(rect.x, rect.y, rect.width, rect.height, text) {
        init { active = false }
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val font = Minecraft.getInstance().font
            val line = MccHubKit.fitted(text, rect.width)
            graphics.drawString(font, line, rect.x + (rect.width - font.width(line)) / 2, rect.y,
                MccHubKit.panelText(CobblemonUiThemes.registry.snapshot()), false)
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    /** One line whose text is read every frame, for the selection countdown. */
    private class LiveLine(
        private val rect: UiRect,
        private val alignEnd: Boolean = false,
        private val color: (UiThemeSnapshot) -> Int,
        private val text: () -> Component,
    ) : AbstractWidget(rect.x, rect.y, rect.width, rect.height, Component.empty()) {
        init { active = false }
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val font = Minecraft.getInstance().font
            val line = MccHubKit.fitted(text(), rect.width)
            val x = if (alignEnd) rect.right - font.width(line) else rect.x
            val y = if (rect.height > font.lineHeight + 2) rect.y + (rect.height - 1 - font.lineHeight) / 2 + 1 else rect.y
            graphics.drawString(font, line, x, y, color(CobblemonUiThemes.registry.snapshot()), false)
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    private companion object {
        const val TEAM_SIZE = 6

        fun remainingSeconds(state: PvpSelectionViewState): Long =
            ((state.selectionDeadlineEpochMillis - System.currentTimeMillis()).coerceAtLeast(0) + 999) / 1_000
    }
}

/** PvP inside whatever rectangle of the hub content area it is given: a strip, the view's cards and a footer. */
internal data class PvpHubLayout(val strip: UiRect, val body: UiRect, val footer: UiRect) {
    companion object {
        fun calculate(bounds: UiRect): PvpHubLayout {
            val gap = MccHubKit.GAP
            val strip = UiRect(bounds.x, bounds.y, bounds.width, MccHubKit.STRIP_HEIGHT)
            val footer = UiRect(bounds.x, bounds.bottom - MccHubKit.FOOTER_HEIGHT, bounds.width, MccHubKit.FOOTER_HEIGHT)
            val body = UiRect(bounds.x, strip.bottom + gap, bounds.width, (footer.y - gap - strip.bottom - gap).coerceAtLeast(1))
            return PvpHubLayout(strip, body, footer)
        }
    }
}

private fun room(key: String, vararg args: Any): Component = Component.translatable("screen.more_cobblemon_contents.pvp.room.$key", *args)
private fun pvp(key: String, vararg args: Any): Component = Component.translatable("screen.more_cobblemon_contents.pvp.$key", *args)
private fun speciesName(speciesId: String): Component = Component.translatable("cobblemon.species.${speciesId.substringAfter(':')}.name")

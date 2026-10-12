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
import jbro.cobblemon.uikit.UiLayout
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
    var listFeedbackArgs: List<String> = emptyList()
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

    fun acceptRooms(rooms: List<PvpRoomSummaryView>, memberRoomId: UUID? = null) {
        PvpRoomClientState.lastRooms = rooms
        listFeedbackKey = null
        room = null
        selection?.let { current ->
            if (!PvpRoomNavigationContract.keepsSelectionAfterRoomList(current.state.spectatorMode, memberRoomId)) selection = null
        }
        if (view != View.SELECTION || selection == null) view = View.LIST
        show()
    }

    /** This player is out of their room (a confirmed spectator exit): forget it so no cached view rejoins it. */
    fun leftRoom() {
        PvpRoomClientState.lastRoom = null
        room = null
        selection?.let { current ->
            if (!PvpRoomNavigationContract.keepsSelectionAfterRoomList(current.state.spectatorMode, null)) selection = null
        }
        if (view != View.SELECTION || selection == null) view = View.LIST
    }

    fun acceptRoom(requestId: UUID?, state: PvpRoomClientView, reopen: Boolean) {
        PvpRoomClientState.lastRoom = state
        val droppedPreview = dropStaleSpectatorPreview(state)
        val requested = PvpRoomNavigationContract.shouldOpen(requestId, PvpRoomClientState.pendingOpenRequests, reopen)
        val current = room
        if (current != null && current.state.roomId == state.roomId &&
            (view == View.ROOM || view == View.PICKER || droppedPreview)
        ) {
            current.applyState(requestId, state)
            if (droppedPreview) view = View.ROOM
            MccHubScreen.refresh(CONTENT)
            return
        }
        if (!requested && !droppedPreview) return
        room = PvpRoomScreenController(state) { intent -> PvpPlayClientNetworking.send(PvpRoomIntentPayload(intent)) }
        if (view != View.SELECTION) view = View.ROOM
        // A preview that ended under a spectator swaps to the room in place; it does not open the hub over a battle.
        if (requested) show() else MccHubScreen.refresh(CONTENT)
    }

    /**
     * Drops a spectator's team preview once [state] shows its room past the preview (the match started, or it was
     * cancelled back to the lobby). The server closes the preview for the two players only by message, and a stale
     * preview's one button would send a lounge exit that takes the spectator out of the room. True when dropped.
     */
    private fun dropStaleSpectatorPreview(state: PvpRoomClientView): Boolean {
        val current = selection ?: return false
        if (PvpRoomNavigationContract.keepsSpectatorPreview(
                current.state.spectatorMode,
                current.state.matchId,
                state.roomId,
                state.phase,
            )
        ) {
            return false
        }
        selection = null
        if (view == View.SELECTION) view = if (room != null) View.ROOM else View.LIST
        return true
    }

    fun rejectRoom(requestId: UUID, messageKey: String, messageArgs: List<String> = emptyList()) {
        PvpRoomClientState.pendingOpenRequests.remove(requestId)
        val current = room
        when {
            current != null && (view == View.ROOM || view == View.PICKER) && MccHubScreen.showing(CONTENT) -> {
                current.applyRejected(requestId, messageKey, messageArgs)
                MccHubScreen.refresh(CONTENT)
            }
            view == View.LIST && MccHubScreen.showing(CONTENT) -> {
                listFeedbackKey = messageKey
                listFeedbackArgs = messageArgs
                MccHubScreen.refresh(CONTENT)
            }
            else -> Minecraft.getInstance().player?.displayClientMessage(
                Component.translatable(messageKey, *messageArgs.toTypedArray()), false)
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
        openSelection()
    }

    /** The team preview opens as its own screen over the arena, not inside the hub. */
    fun openSelection() {
        val controller = selection ?: return
        Minecraft.getInstance().setScreen(PvpSelectionScreen(controller))
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
        val screen = Minecraft.getInstance().screen
        if (MccHubScreen.showing(CONTENT) || screen is PvpSelectionScreen) Minecraft.getInstance().setScreen(null)
    }

    /** The HUD's open control: the last room this client was in, refreshed through the join handshake when shown. */
    fun showLastRoom() {
        if (view == View.SELECTION && selection != null) {
            openSelection()
            return
        }
        val last = PvpRoomClientState.lastRoom ?: return
        dropStaleSpectatorPreview(last)
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
            val feedback = Component.translatable(key, *PvpHubClient.listFeedbackArgs.toTypedArray())
            val (line, rest) = MccHubKit.lineAbove(body, feedbackHeight(feedback, body.width))
            MccHubKit.text(host, line, feedback) { it.colors.accentDanger }
            body = rest
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
        host.add(SpectatorCount(layout.strip, room("spectators", state.spectators.size), state.spectators))
        val (left, right, settings) = MccHubKit.columns(layout.body, 3, 3, 4)
        seat(host, left, PvpRoomSide.LEFT, state.leftPlayer, controller, me, lobby)
        seat(host, right, PvpRoomSide.RIGHT, state.rightPlayer, controller, me, lobby)

        val body = MccHubKit.card(host, settings, room("settings"))
        val editable = isHost && lobby && idle
        val mechanics = state.settings.immutableEnabledMechanics
        val rows = listOf(
            PvpRoomSettingRows.Row(room("group.visibility"),
                PvpRoomVisibility.entries.map { PvpRoomSettingRows.Option(it.name.lowercase(), room("visibility.${it.name.lowercase()}")) },
                setOf(state.settings.visibility.name.lowercase()), editable) { id ->
                val next = PvpRoomVisibility.entries.first { it.name.lowercase() == id }
                if (next != state.settings.visibility) updateSettings(controller, state.settings.copy(visibility = next))
            },
            PvpRoomSettingRows.Row(room("group.format"),
                PvpBattleFormat.entries.map { PvpRoomSettingRows.Option(it.recordId, room("format.${it.recordId}")) },
                setOf(state.settings.format.recordId), editable) { id ->
                val next = PvpBattleFormat.entries.first { it.recordId == id }
                if (next != state.settings.format) updateSettings(controller, state.settings.copy(format = next))
            },
            PvpRoomSettingRows.Row(room("group.mechanics"),
                PvpBattleMechanic.entries.map { PvpRoomSettingRows.Option(it.id, room("mechanic.${it.id}")) },
                mechanics.mapTo(HashSet()) { it.id }, editable, room("group.mechanics")) { id ->
                val next = LinkedHashSet(mechanics)
                val mechanic = PvpBattleMechanic.entries.first { it.id == id }
                if (!next.add(mechanic)) next.remove(mechanic)
                updateSettings(controller, state.settings.copy(enabledMechanics = next))
            },
        )
        val feedback = roomFeedback(controller)
        val settingsBottom = PvpRoomSettingRows.place(host, body, rows)
        val parts = UiLayout.column(gap = MccHubKit.GAP) {
            if (feedback != null) fixed(feedbackHeight(feedback, body.width), "feedback")
            weight("spectators")
        }.solve(MccHubKit.below(body, settingsBottom + MccHubKit.GAP + 3))
        feedback?.let { MccHubKit.text(host, parts["feedback"], it) { theme -> theme.colors.accentDanger } }
        val spectators = parts["spectators"]
        if (spectators.height >= 10 && state.spectators.isNotEmpty()) host.add(Faces(spectators, state.spectators))

        val members = (listOfNotNull(state.leftPlayer, state.rightPlayer) + state.spectators).distinctBy(PvpRoomMemberView::playerId)
        val manageable = isHost && lobby && idle && (state.inviteCandidates.isNotEmpty() || members.any { it.playerId != state.hostId })
        MccHubKit.footer(host, layout.footer,
            listOf(
                MccHubKit.Action(room("leave"), UiButtonVariant.DANGER,
                    idle && PvpRoomNavigationContract.canLeave(lobby, state.spectators.any { it.playerId == me })) { controller.submit(PvpRoomIntent.Leave(UUID.randomUUID(), state.roomId)) },
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
        val parts = UiLayout.column {
            weight("model", min = 8)
            space(3)
            fixed(10, "name")
            space(1)
            fixed(MccHubKit.CONTROL_HEIGHT, "button")
        }.solve(body)
        val model = parts["model"]
        val buttonRect = parts["button"]
        val profile = occupant?.let { Minecraft.getInstance().connection?.getPlayerInfo(it.playerId)?.profile }
        if (occupant == null || profile == null) {
            MccHubKit.placeholder(host, model, if (occupant == null) Component.literal("+") else Component.literal(occupant.name))
        } else {
            host.add(CobblemonUiRenderSlot.create(model.x, model.y, model.width, model.height, UiRenderSlotSpec(Component.literal(occupant.name)),
                CobblemonUiRenderContent.PlayerProfile(profile, UiModelFraming.FULL_BODY)))
        }
        if (occupant != null) {
            val name = Component.literal(occupant.name).append(if (occupant.playerId == state.hostId) room("host_suffix") else Component.empty())
            host.add(CenteredLine(parts["name"], name))
        }
        val mine = occupant != null && occupant.playerId == me
        val label = when {
            occupant == null -> room("join_side")
            mine -> room("observe")
            else -> Component.literal(occupant.name)
        }
        val button = CobblemonUiButton.create(buttonRect.x, buttonRect.y, buttonRect.width,
            UiButtonSpec(MccHubKit.fitted(label, buttonRect.width - 12), variant = if (mine) UiButtonVariant.SECONDARY else UiButtonVariant.PRIMARY,
                size = UiControlSize.MEDIUM, width = UiWidthPolicy.Fixed(buttonRect.width))) {
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
        host.add(SpectatorCount(layout.strip, room("spectators", state.spectators.size), state.spectators))
        val body = MccHubKit.card(host, layout.body, room(if (inviting) "picker.invite" else "picker.transfer"), MccHubKit.CardTone.FEATURE)
        val listTop = MccHubKit.choices(host, MccHubKit.lineAbove(body, MccHubKit.CONTROL_HEIGHT).first, listOf(
            MccHubKit.ChoiceRow(room("invite_manage"), listOf(
                MccHubKit.Choice("invite", room("invite_manage")),
                MccHubKit.Choice("transfer", room("transfer_manage")),
            ), if (inviting) "invite" else "transfer", !controller.isPending) { id ->
                PvpHubClient.navigate(PvpHubClient.View.PICKER, invite = id == "invite")
            },
        )) + MccHubKit.GAP + 3
        val candidates = if (inviting) state.inviteCandidates else members
        MccHubKit.pagedList(host, MccHubKit.below(body, listTop, min = 1), candidates.map { member ->
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

    /** The team preview runs in its own screen; the tab only offers the way back to it. */
    private fun buildSelection(host: MccHubContentHost, layout: PvpHubLayout, controller: PvpSelectionScreenController) {
        val state = controller.state
        MccHubKit.strip(host, layout.strip,
            Component.empty().append(pvp("title")).append(" · ").append(pvp("format.${state.format.recordId}")))
        val body = MccHubKit.card(host, layout.body, pvp("title"), MccHubKit.CardTone.FEATURE)
        MccHubKit.text(host, body, pvp("select.reopen_hint"))
        MccHubKit.footer(host, layout.footer, emptyList(),
            listOf(MccHubKit.Action(pvp("select.reopen"), UiButtonVariant.PRIMARY, minWidth = 96) { PvpHubClient.openSelection() }))
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
            val grid = PvpSpectatorGridLayout.calculate(rect, members.map { font.width(it.name) })
            grid.slots.forEach { slot ->
                val member = members[slot.index]
                connection?.getPlayerInfo(member.playerId)?.profile?.let { profile ->
                    CobblemonUiRenderSlot.drawContent(graphics, slot.face,
                        CobblemonUiRenderContent.PlayerFace(profile), partialTick)
                }
                graphics.drawString(font, font.plainSubstrByWidth(member.name, slot.nameWidth), slot.nameLeft, slot.bounds.y + 2,
                    MccHubKit.panelText(theme), false)
            }
            // Hovering a spectator shows their face large with the whole name, for faces too small to tell apart.
            grid.slots.firstOrNull { mouseX in it.bounds.x until it.bounds.right && mouseY in it.bounds.y until it.bounds.bottom }
                ?.let { PvpSpectatorCard.drawOne(graphics, mouseX, mouseY, members[it.index]) }
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    /**
     * Over the strip's spectator count, where [MccHubKit.strip] right-aligns it: draws nothing, and hovering it
     * lists who is watching.
     */
    private class SpectatorCount(private val strip: UiRect, private val label: Component, private val spectators: List<PvpRoomMemberView>) :
        AbstractWidget(0, strip.y, 0, strip.height, label) {
        init {
            active = false
            val labelWidth = Minecraft.getInstance().font.width(label)
            x = strip.right - STRIP_END_PADDING - labelWidth
            width = labelWidth
        }
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            if (spectators.isEmpty() || mouseX !in x until x + width || mouseY !in y until y + height) return
            val shown = spectators.take(MAX_LISTED_SPECTATORS)
            PvpSpectatorCard.drawList(graphics, mouseX, mouseY, label, shown,
                if (spectators.size > shown.size) room("hud.more_spectators", spectators.size - shown.size) else null)
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
        /** [MccHubKit.strip]'s inset of its end text from the right edge. */
        const val STRIP_END_PADDING = 6
        const val MAX_LISTED_SPECTATORS = 20

        fun remainingSeconds(state: PvpSelectionViewState): Long =
            ((state.selectionDeadlineEpochMillis - System.currentTimeMillis()).coerceAtLeast(0) + 999) / 1_000
    }
}

/** PvP inside whatever rectangle of the hub content area it is given: a strip, the view's cards and a footer. */
internal data class PvpHubLayout(val strip: UiRect, val body: UiRect, val footer: UiRect) {
    companion object {
        fun calculate(bounds: UiRect): PvpHubLayout {
            val layout = MccHubKit.tabFrame(UiLayout.leaf("body")).solve(bounds)
            return PvpHubLayout(layout["strip"], layout["body"], layout["footer"])
        }
    }
}

private const val MAX_FEEDBACK_LINES = 3

private fun roomFeedback(controller: PvpRoomScreenController): Component? =
    controller.feedbackKey?.let { Component.translatable(it, *controller.feedbackArgs.toTypedArray()) }

/** Room for [text] wrapped to [width], so a long rejection reads in full instead of losing its end. */
private fun feedbackHeight(text: Component, width: Int): Int {
    val font = Minecraft.getInstance().font
    return font.split(text, width.coerceAtLeast(1)).size.coerceIn(1, MAX_FEEDBACK_LINES) * (font.lineHeight + 1)
}

private fun room(key: String, vararg args: Any): Component = Component.translatable("screen.more_cobblemon_contents.pvp.room.$key", *args)
private fun pvp(key: String, vararg args: Any): Component = Component.translatable("screen.more_cobblemon_contents.pvp.$key", *args)
private fun speciesName(speciesId: String): Component = Component.translatable("cobblemon.species.${speciesId.substringAfter(':')}.name")

package jbro.cobblemon.mcc.client

import java.util.UUID
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.pvp.PvpBattleFormat
import jbro.cobblemon.mcc.internal.pvp.PvpBattleMechanic
import jbro.cobblemon.mcc.internal.pvp.PvpRoomPhase
import jbro.cobblemon.mcc.internal.pvp.PvpRoomSettings
import jbro.cobblemon.mcc.internal.pvp.PvpRoomSide
import jbro.cobblemon.mcc.internal.pvp.PvpRoomVisibility
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomClientView
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomIntent
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomIntentPayload
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomMemberView
import jbro.cobblemon.mcc.internal.pvp.ui.PvpRoomScreenController
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.PlayerFaceRenderer
import net.minecraft.network.chat.Component

internal class PvpRoomScreen(
    initialState: PvpRoomClientView,
    private val roomList: PvpRoomListScreen,
) : MccScreen(Component.translatable(key("title"))) {
    private val controller = PvpRoomScreenController(initialState) { intent ->
        PvpPlayClientNetworking.send(PvpRoomIntentPayload(intent))
    }
    private val state: PvpRoomClientView
        get() = controller.state
    private val feedbackKey: String?
        get() = controller.feedbackKey
    private val playerModels = MccPlayerModelRenderer()

    override fun init() = buildWidgets()

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val layout = PvpRoomLayout.calculate(width, height)
        MccGuiSurface.drawBackdrop(graphics, width, height)
        MccGuiSurface.drawShell(graphics, layout.shell)
        MccGuiSurface.drawPanel(graphics, layout.header, MccGuiPalette.ACCENT_SECONDARY, alternate = true)
        MccGuiSurface.drawPanel(graphics, layout.visibilityGroup, MccGuiPalette.BORDER_BRIGHT, alternate = true)
        MccGuiSurface.drawPanel(graphics, layout.formatGroup, MccGuiPalette.BORDER_BRIGHT, alternate = true)
        MccGuiSurface.drawPanel(graphics, layout.mechanicsGroup, MccGuiPalette.BORDER_BRIGHT, alternate = true)
        MccGuiSurface.drawPanel(graphics, layout.leftSeat, MccGuiPalette.ACCENT_PRIMARY)
        MccGuiSurface.drawPanel(graphics, layout.spectators, MccGuiPalette.BORDER_BRIGHT, alternate = true)
        MccGuiSurface.drawPanel(graphics, layout.rightSeat, MccGuiPalette.ACCENT_SECONDARY)
        MccGuiSurface.drawPanel(graphics, layout.footer, MccGuiPalette.BORDER_BRIGHT, alternate = true)
        graphics.drawString(font, title, layout.header.left + 7, layout.header.top + 7, MccGuiPalette.ACCENT_PRIMARY, false)
        graphics.drawCenteredString(font, Component.translatable(key("group.visibility")), layout.visibilityGroup.left + layout.visibilityGroup.width / 2, layout.visibilityGroup.top + 5, MccGuiPalette.TEXT_SECONDARY)
        graphics.drawCenteredString(font, Component.translatable(key("group.format")), layout.formatGroup.left + layout.formatGroup.width / 2, layout.formatGroup.top + 5, MccGuiPalette.TEXT_SECONDARY)
        graphics.drawCenteredString(font, Component.translatable(key("group.mechanics")), layout.mechanicsGroup.left + layout.mechanicsGroup.width / 2, layout.mechanicsGroup.top + 5, MccGuiPalette.TEXT_SECONDARY)
        playerModels.retain(setOfNotNull(state.leftPlayer?.playerId, state.rightPlayer?.playerId))
        drawSeat(graphics, layout, layout.leftSeat, state.leftPlayer, PvpRoomSide.LEFT)
        drawSeat(graphics, layout, layout.rightSeat, state.rightPlayer, PvpRoomSide.RIGHT)
        drawSpectators(graphics, layout)
        feedbackKey?.let {
            graphics.drawCenteredString(font, Component.translatable(it), width / 2, layout.footer.top - 11, MccGuiPalette.ACCENT_DANGER)
        }
        super.render(graphics, mouseX, mouseY, partialTick)
    }

    fun applyRejected(requestId: UUID, messageKey: String) {
        controller.applyRejected(requestId, messageKey)
        rebuild()
    }

    fun applyState(requestId: UUID?, newState: PvpRoomClientView) {
        controller.applyState(requestId, newState)
        PvpRoomClientState.lastRoom = newState
        rebuild()
    }

    override fun onClose() {
        minecraft?.setScreen(roomList)
    }

    private fun drawSeat(
        graphics: GuiGraphics,
        layout: PvpRoomLayout,
        panel: MccRect,
        member: PvpRoomMemberView?,
        side: PvpRoomSide,
    ) {
        val heading = Component.translatable(key("side.${side.name.lowercase()}"))
        graphics.drawCenteredString(font, heading, panel.left + panel.width / 2, panel.top + 7, MccGuiPalette.TEXT_SECONDARY)
        if (member == null) {
            val modelBottom = layout.seatButton(panel).top - 4
            graphics.drawCenteredString(font, Component.literal("+"), panel.left + panel.width / 2, panel.top + (modelBottom - panel.top) / 2, MccGuiPalette.TEXT_DIM)
            return
        }
        renderPlayerModel(graphics, member, panel, layout.seatButton(panel).top - 13)
        val suffix = if (member.playerId == state.hostId) Component.translatable(key("host_suffix")) else Component.empty()
        val label = Component.literal(member.name).append(suffix)
        val clipped = font.plainSubstrByWidth(label.string, (panel.width - 10).coerceAtLeast(1))
        graphics.drawCenteredString(
            font,
            Component.literal(clipped),
            panel.left + panel.width / 2,
            layout.seatButton(panel).top - 11,
            MccGuiPalette.TEXT_PRIMARY,
        )
    }

    private fun renderPlayerModel(graphics: GuiGraphics, member: PvpRoomMemberView, panel: MccRect, bottom: Int) {
        val client = minecraft ?: return
        val profile = client.connection?.getPlayerInfo(member.playerId)?.profile ?: return
        val scale = (panel.height / 5).coerceIn(20, 32)
        val modelTop = panel.top + 17
        val centerY = PlayerModelCentering.centerY(modelTop, bottom + 1)
        graphics.enableScissor(panel.left + 2, modelTop, panel.right - 2, bottom + 1)
        playerModels.render(graphics, member.playerId, profile, panel.left + panel.width / 2, centerY, scale)
        graphics.disableScissor()
    }

    private fun drawSpectators(graphics: GuiGraphics, layout: PvpRoomLayout) {
        val panel = layout.spectators
        val label = Component.translatable(key("spectators"), state.spectators.size)
        val phase = Component.translatable(key("phase.${state.phase.name.lowercase()}"))
        val heading = Component.empty().append(label).append(" · ").append(phase)
        graphics.drawCenteredString(font, heading, panel.left + panel.width / 2, panel.top + 7, MccGuiPalette.TEXT_SECONDARY)
        val grid = PvpSpectatorGridLayout.calculate(layout.spectatorGrid, state.spectators.map { font.width(it.name) })
        grid.slots.forEach { slot ->
            val member = state.spectators[slot.index]
            val skin = minecraft?.connection?.getPlayerInfo(member.playerId)?.skin
            if (skin != null) PlayerFaceRenderer.draw(graphics, skin, slot.face.left, slot.face.top, slot.face.width)
            val clipped = font.plainSubstrByWidth(member.name, slot.nameWidth)
            graphics.drawString(font, clipped, slot.nameLeft, slot.bounds.top + 2, MccGuiPalette.TEXT_PRIMARY, false)
        }
    }

    private fun buildWidgets() {
        val layout = PvpRoomLayout.calculate(width, height)
        val playerId = minecraft?.player?.uuid ?: return
        val host = state.hostId == playerId
        val lobby = state.phase == PvpRoomPhase.LOBBY

        addRenderableWidget(MccStyledButton(layout.closeButton, Component.literal("×"), MccButtonTone.DANGER) { onClose() })
        addSeatButton(PvpRoomSide.LEFT, layout, layout.leftSeat, state.leftPlayer, playerId, lobby)
        addSeatButton(PvpRoomSide.RIGHT, layout, layout.rightSeat, state.rightPlayer, playerId, lobby)

        val visibilityOptions = layout.visibilityButtons()
        val formatOptions = layout.formatButtons()
        val mechanicOptions = layout.mechanicButtons()
        addOptionButton(
            visibilityOptions[0],
            Component.translatable(key("visibility.public")),
            state.settings.visibility == PvpRoomVisibility.PUBLIC,
            host && lobby && !controller.isPending,
        ) { updateSettings(state.settings.copy(visibility = PvpRoomVisibility.PUBLIC)) }
        addOptionButton(
            visibilityOptions[1],
            Component.translatable(key("visibility.private")),
            state.settings.visibility == PvpRoomVisibility.PRIVATE,
            host && lobby && !controller.isPending,
        ) { updateSettings(state.settings.copy(visibility = PvpRoomVisibility.PRIVATE)) }
        addOptionButton(
            formatOptions[0],
            Component.translatable(key("format.single")),
            state.settings.format == PvpBattleFormat.SINGLE,
            host && lobby && !controller.isPending,
        ) { updateSettings(state.settings.copy(format = PvpBattleFormat.SINGLE)) }
        addOptionButton(
            formatOptions[1],
            Component.translatable(key("format.double")),
            state.settings.format == PvpBattleFormat.DOUBLE,
            host && lobby && !controller.isPending,
        ) { updateSettings(state.settings.copy(format = PvpBattleFormat.DOUBLE)) }

        PvpBattleMechanic.entries.forEachIndexed { index, mechanic ->
            addOptionButton(
                mechanicOptions[index],
                Component.translatable(key("mechanic.${mechanic.id}")),
                mechanic in state.settings.immutableEnabledMechanics,
                host && lobby && !controller.isPending,
            ) { toggleMechanic(mechanic) }
        }

        val actions = layout.managementButtons()
        val selfSeated = state.leftPlayer?.playerId == playerId || state.rightPlayer?.playerId == playerId
        addRenderableWidget(MccStyledButton(layout.spectatorJoinButton, Component.translatable(key("observe")), selected = !selfSeated) {
            send(PvpRoomIntent.Observe(UUID.randomUUID(), state.roomId))
        }.also { it.active = lobby && selfSeated && !controller.isPending })

        val members = listOfNotNull(state.leftPlayer, state.rightPlayer) + state.spectators
        val transferCandidates = members.distinctBy(PvpRoomMemberView::playerId).filter { it.playerId != state.hostId }
        addRenderableWidget(MccStyledButton(actions[0], Component.translatable(key("invite_manage")), MccButtonTone.SECONDARY) {
            openPicker(true, state.inviteCandidates)
        }.also { it.active = host && lobby && state.inviteCandidates.isNotEmpty() && !controller.isPending })
        addRenderableWidget(MccStyledButton(actions[1], Component.translatable(key("transfer_manage"))) {
            openPicker(false, transferCandidates)
        }.also { it.active = host && lobby && transferCandidates.isNotEmpty() && !controller.isPending })
        addRenderableWidget(MccStyledButton(actions[2], Component.translatable(key("start")), MccButtonTone.PRIMARY) {
            send(PvpRoomIntent.Start(UUID.randomUUID(), state.roomId))
        }.also { it.active = host && lobby && state.leftPlayer != null && state.rightPlayer != null && !controller.isPending })
        addRenderableWidget(MccStyledButton(actions[3], Component.translatable(key("leave")), MccButtonTone.DANGER) {
            send(PvpRoomIntent.Leave(UUID.randomUUID(), state.roomId))
        }.also { it.active = !controller.isPending })
    }

    private fun addSeatButton(
        side: PvpRoomSide,
        layout: PvpRoomLayout,
        panel: MccRect,
        occupant: PvpRoomMemberView?,
        playerId: UUID,
        lobby: Boolean,
    ) {
        val bounds = layout.seatButton(panel)
        val label = when {
            occupant == null -> Component.translatable(key("join_side"))
            occupant.playerId == playerId -> Component.translatable(key("your_side"))
            else -> Component.literal(occupant.name)
        }
        addRenderableWidget(MccStyledButton(bounds, label, MccButtonTone.PRIMARY, occupant?.playerId == playerId) {
            send(PvpRoomIntent.ClaimSeat(UUID.randomUUID(), state.roomId, side))
        }.also { it.active = lobby && occupant == null && !controller.isPending })
    }

    private fun openPicker(invite: Boolean, members: List<PvpRoomMemberView>) {
        minecraft?.setScreen(
            PvpMemberPickerScreen(
                this,
                Component.translatable(key(if (invite) "picker.invite" else "picker.transfer")),
                members,
            ) { member ->
                if (invite) {
                    send(PvpRoomIntent.Invite(UUID.randomUUID(), state.roomId, member.playerId))
                } else {
                    send(PvpRoomIntent.TransferHost(UUID.randomUUID(), state.roomId, member.playerId))
                }
            },
        )
    }

    private fun addOptionButton(
        bounds: MccRect,
        label: Component,
        selected: Boolean,
        enabled: Boolean,
        action: () -> Unit,
    ) {
        addRenderableWidget(MccStyledButton(bounds, label, MccButtonTone.SECONDARY, selected, action).also {
            it.active = enabled
        })
    }

    private fun updateSettings(settings: PvpRoomSettings) {
        send(PvpRoomIntent.UpdateSettings(UUID.randomUUID(), state.roomId, settings))
    }

    private fun toggleMechanic(mechanic: PvpBattleMechanic) {
        val mechanics = LinkedHashSet(state.settings.immutableEnabledMechanics)
        if (!mechanics.add(mechanic)) mechanics.remove(mechanic)
        updateSettings(state.settings.copy(enabledMechanics = mechanics))
    }

    private fun send(intent: PvpRoomIntent) = controller.submit(intent)

    private fun rebuild() {
        clearWidgets()
        buildWidgets()
    }

    private companion object {
        fun key(path: String) = "screen.${MoreCobblemonContents.MOD_ID}.pvp.room.$path"
    }
}

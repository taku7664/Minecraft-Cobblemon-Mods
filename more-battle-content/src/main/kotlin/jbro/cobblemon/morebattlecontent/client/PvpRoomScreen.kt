package jbro.cobblemon.morebattlecontent.client

import java.util.UUID
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleFormat
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleMechanic
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomPhase
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomSettings
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomSide
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomVisibility
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpRoomClientView
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpRoomIntent
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpRoomIntentPayload
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpRoomMemberView
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.PlayerFaceRenderer
import net.minecraft.network.chat.Component

internal class PvpRoomScreen(
    private var state: PvpRoomClientView,
    private val roomList: PvpRoomListScreen,
) : MbcScreen(Component.translatable(key("title"))) {
    private var feedbackKey: String? = null
    private val playerModels = PvpRoomPlayerModelRenderer()

    override fun init() = buildWidgets()

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val layout = PvpRoomLayout.calculate(width, height)
        MbcGuiSurface.drawBackdrop(graphics, width, height)
        MbcGuiSurface.drawShell(graphics, layout.shell)
        MbcGuiSurface.drawPanel(graphics, layout.header, MbcGuiPalette.ACCENT_SECONDARY, alternate = true)
        MbcGuiSurface.drawPanel(graphics, layout.visibilityGroup, MbcGuiPalette.BORDER_BRIGHT, alternate = true)
        MbcGuiSurface.drawPanel(graphics, layout.formatGroup, MbcGuiPalette.BORDER_BRIGHT, alternate = true)
        MbcGuiSurface.drawPanel(graphics, layout.mechanicsGroup, MbcGuiPalette.BORDER_BRIGHT, alternate = true)
        MbcGuiSurface.drawPanel(graphics, layout.leftSeat, MbcGuiPalette.ACCENT_PRIMARY)
        MbcGuiSurface.drawPanel(graphics, layout.spectators, MbcGuiPalette.BORDER_BRIGHT, alternate = true)
        MbcGuiSurface.drawPanel(graphics, layout.rightSeat, MbcGuiPalette.ACCENT_SECONDARY)
        MbcGuiSurface.drawPanel(graphics, layout.footer, MbcGuiPalette.BORDER_BRIGHT, alternate = true)
        graphics.drawString(font, title, layout.header.left + 7, layout.header.top + 7, MbcGuiPalette.ACCENT_PRIMARY, false)
        graphics.drawCenteredString(font, Component.translatable(key("group.visibility")), layout.visibilityGroup.left + layout.visibilityGroup.width / 2, layout.visibilityGroup.top + 5, MbcGuiPalette.TEXT_SECONDARY)
        graphics.drawCenteredString(font, Component.translatable(key("group.format")), layout.formatGroup.left + layout.formatGroup.width / 2, layout.formatGroup.top + 5, MbcGuiPalette.TEXT_SECONDARY)
        graphics.drawCenteredString(font, Component.translatable(key("group.mechanics")), layout.mechanicsGroup.left + layout.mechanicsGroup.width / 2, layout.mechanicsGroup.top + 5, MbcGuiPalette.TEXT_SECONDARY)
        playerModels.retain(setOfNotNull(state.leftPlayer?.playerId, state.rightPlayer?.playerId))
        drawSeat(graphics, layout, layout.leftSeat, state.leftPlayer, PvpRoomSide.LEFT)
        drawSeat(graphics, layout, layout.rightSeat, state.rightPlayer, PvpRoomSide.RIGHT)
        drawSpectators(graphics, layout)
        feedbackKey?.let {
            graphics.drawCenteredString(font, Component.translatable(it), width / 2, layout.footer.top - 11, MbcGuiPalette.ACCENT_DANGER)
        }
        super.render(graphics, mouseX, mouseY, partialTick)
    }

    fun applyRejected(messageKey: String) {
        feedbackKey = messageKey
    }

    fun applyState(newState: PvpRoomClientView) {
        state = newState
        PvpRoomClientState.lastRoom = newState
        rebuild()
    }

    override fun onClose() {
        minecraft?.setScreen(roomList)
    }

    private fun drawSeat(
        graphics: GuiGraphics,
        layout: PvpRoomLayout,
        panel: TowerPlayRect,
        member: PvpRoomMemberView?,
        side: PvpRoomSide,
    ) {
        val heading = Component.translatable(key("side.${side.name.lowercase()}"))
        graphics.drawCenteredString(font, heading, panel.left + panel.width / 2, panel.top + 7, MbcGuiPalette.TEXT_SECONDARY)
        if (member == null) {
            val modelBottom = layout.seatButton(panel).top - 4
            graphics.drawCenteredString(font, Component.literal("+"), panel.left + panel.width / 2, panel.top + (modelBottom - panel.top) / 2, MbcGuiPalette.TEXT_DIM)
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
            MbcGuiPalette.TEXT_PRIMARY,
        )
    }

    private fun renderPlayerModel(graphics: GuiGraphics, member: PvpRoomMemberView, panel: TowerPlayRect, bottom: Int) {
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
        graphics.drawCenteredString(font, heading, panel.left + panel.width / 2, panel.top + 7, MbcGuiPalette.TEXT_SECONDARY)
        val grid = PvpSpectatorGridLayout.calculate(layout.spectatorGrid, state.spectators.map { font.width(it.name) })
        grid.slots.forEach { slot ->
            val member = state.spectators[slot.index]
            val skin = minecraft?.connection?.getPlayerInfo(member.playerId)?.skin
            if (skin != null) PlayerFaceRenderer.draw(graphics, skin, slot.face.left, slot.face.top, slot.face.width)
            val clipped = font.plainSubstrByWidth(member.name, slot.nameWidth)
            graphics.drawString(font, clipped, slot.nameLeft, slot.bounds.top + 2, MbcGuiPalette.TEXT_PRIMARY, false)
        }
    }

    private fun buildWidgets() {
        val layout = PvpRoomLayout.calculate(width, height)
        val playerId = minecraft?.player?.uuid ?: return
        val host = state.hostId == playerId
        val lobby = state.phase == PvpRoomPhase.LOBBY

        addRenderableWidget(MbcStyledButton(layout.closeButton, Component.literal("×"), MbcButtonTone.DANGER) { onClose() })
        addSeatButton(PvpRoomSide.LEFT, layout, layout.leftSeat, state.leftPlayer, playerId, lobby)
        addSeatButton(PvpRoomSide.RIGHT, layout, layout.rightSeat, state.rightPlayer, playerId, lobby)

        val visibilityOptions = layout.visibilityButtons()
        val formatOptions = layout.formatButtons()
        val mechanicOptions = layout.mechanicButtons()
        addOptionButton(
            visibilityOptions[0],
            Component.translatable(key("visibility.public")),
            state.settings.visibility == PvpRoomVisibility.PUBLIC,
            host && lobby,
        ) { updateSettings(state.settings.copy(visibility = PvpRoomVisibility.PUBLIC)) }
        addOptionButton(
            visibilityOptions[1],
            Component.translatable(key("visibility.private")),
            state.settings.visibility == PvpRoomVisibility.PRIVATE,
            host && lobby,
        ) { updateSettings(state.settings.copy(visibility = PvpRoomVisibility.PRIVATE)) }
        addOptionButton(
            formatOptions[0],
            Component.translatable(key("format.single")),
            state.settings.format == PvpBattleFormat.SINGLE,
            host && lobby,
        ) { updateSettings(state.settings.copy(format = PvpBattleFormat.SINGLE)) }
        addOptionButton(
            formatOptions[1],
            Component.translatable(key("format.double")),
            state.settings.format == PvpBattleFormat.DOUBLE,
            host && lobby,
        ) { updateSettings(state.settings.copy(format = PvpBattleFormat.DOUBLE)) }

        PvpBattleMechanic.entries.forEachIndexed { index, mechanic ->
            addOptionButton(
                mechanicOptions[index],
                Component.translatable(key("mechanic.${mechanic.id}")),
                mechanic in state.settings.immutableEnabledMechanics,
                host && lobby,
            ) { toggleMechanic(mechanic) }
        }

        val actions = layout.managementButtons()
        val selfSeated = state.leftPlayer?.playerId == playerId || state.rightPlayer?.playerId == playerId
        addRenderableWidget(MbcStyledButton(layout.spectatorJoinButton, Component.translatable(key("observe")), selected = !selfSeated) {
            send(PvpRoomIntent.Observe(UUID.randomUUID(), state.roomId))
        }.also { it.active = lobby && selfSeated })

        val members = listOfNotNull(state.leftPlayer, state.rightPlayer) + state.spectators
        val transferCandidates = members.distinctBy(PvpRoomMemberView::playerId).filter { it.playerId != state.hostId }
        addRenderableWidget(MbcStyledButton(actions[0], Component.translatable(key("invite_manage")), MbcButtonTone.SECONDARY) {
            openPicker(true, state.inviteCandidates)
        }.also { it.active = host && lobby && state.inviteCandidates.isNotEmpty() })
        addRenderableWidget(MbcStyledButton(actions[1], Component.translatable(key("transfer_manage"))) {
            openPicker(false, transferCandidates)
        }.also { it.active = host && lobby && transferCandidates.isNotEmpty() })
        addRenderableWidget(MbcStyledButton(actions[2], Component.translatable(key("start")), MbcButtonTone.PRIMARY) {
            send(PvpRoomIntent.Start(UUID.randomUUID(), state.roomId))
        }.also { it.active = host && lobby && state.leftPlayer != null && state.rightPlayer != null })
        addRenderableWidget(MbcStyledButton(actions[3], Component.translatable(key("leave")), MbcButtonTone.DANGER) {
            send(PvpRoomIntent.Leave(UUID.randomUUID(), state.roomId))
        })
    }

    private fun addSeatButton(
        side: PvpRoomSide,
        layout: PvpRoomLayout,
        panel: TowerPlayRect,
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
        addRenderableWidget(MbcStyledButton(bounds, label, MbcButtonTone.PRIMARY, occupant?.playerId == playerId) {
            send(PvpRoomIntent.ClaimSeat(UUID.randomUUID(), state.roomId, side))
        }.also { it.active = lobby && occupant == null })
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
        bounds: TowerPlayRect,
        label: Component,
        selected: Boolean,
        enabled: Boolean,
        action: () -> Unit,
    ) {
        addRenderableWidget(MbcStyledButton(bounds, label, MbcButtonTone.SECONDARY, selected, action).also {
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

    private fun send(intent: PvpRoomIntent) = PvpPlayClientNetworking.send(PvpRoomIntentPayload(intent))

    private fun rebuild() {
        clearWidgets()
        buildWidgets()
    }

    private companion object {
        fun key(path: String) = "screen.${MoreBattleContent.MOD_ID}.pvp.room.$path"
    }
}

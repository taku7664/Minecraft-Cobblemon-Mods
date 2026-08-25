package jbro.cobblemon.morebattlecontent.client

import java.util.UUID
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.pvp.network.PvpSelectionIntentPayload
import jbro.cobblemon.morebattlecontent.internal.pvp.ui.PvpSelectionOpponentSlot
import jbro.cobblemon.morebattlecontent.internal.pvp.ui.PvpSelectionPartySlot
import jbro.cobblemon.morebattlecontent.internal.pvp.ui.PvpSelectionScreenController
import jbro.cobblemon.morebattlecontent.internal.pvp.ui.PvpSelectionViewState
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.PlayerFaceRenderer
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component

internal class PvpSelectionScreen(initialState: PvpSelectionViewState) :
    MbcScreen(Component.translatable(key("title"))) {
    private val portraits = MbcPokemonPortraitRenderer()
    private val controller = PvpSelectionScreenController(
        initialState,
        send = { intent -> PvpPlayClientNetworking.send(PvpSelectionIntentPayload(intent)) },
    )

    val matchId: UUID
        get() = controller.state.matchId

    override fun init() = buildWidgets()

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        MbcGuiSurface.drawBackdrop(graphics, width, height)
        MbcGuiSurface.drawShell(graphics, shell())
        MbcGuiSurface.drawPanel(graphics, header(), MbcGuiPalette.ACCENT_SECONDARY, alternate = true)
        MbcGuiSurface.drawPanel(graphics, leftPanel(), MbcGuiPalette.ACCENT_PRIMARY)
        MbcGuiSurface.drawPanel(graphics, centerPanel(), MbcGuiPalette.BORDER_BRIGHT, alternate = true)
        MbcGuiSurface.drawPanel(graphics, rightPanel(), MbcGuiPalette.ACCENT_SECONDARY)
        MbcGuiSurface.drawPanel(graphics, footer(), MbcGuiPalette.BORDER_BRIGHT, alternate = true)
        drawHeader(graphics)
        if (controller.state.spectatorMode) {
            drawPublicTeam(
                graphics,
                leftPanel(),
                controller.state.spectatorLeftParty,
                controller.state.leftPlayerName,
                0,
                partialTick,
            )
            drawPublicTeam(
                graphics,
                rightPanel(),
                controller.state.spectatorRightParty,
                controller.state.rightPlayerName,
                PvpSelectionLayout.PARTY_SIZE,
                partialTick,
            )
        } else {
            drawTeam(graphics, leftPanel(), leftOwn(), controller.state.leftPlayerName, partialTick)
            drawTeam(graphics, rightPanel(), !leftOwn(), controller.state.rightPlayerName, partialTick)
        }
        drawCenter(graphics)
        drawStatus(graphics)
        super.render(graphics, mouseX, mouseY, partialTick)
    }

    override fun onClose() {
        if (controller.state.spectatorMode) leaveSpectating() else confirmCancel()
    }

    fun applyAccepted(requestId: UUID, state: PvpSelectionViewState) {
        controller.applyAccepted(requestId, state)
        rebuild()
    }

    fun applyRejected(requestId: UUID, rejectedMatchId: UUID, messageKey: String) {
        if (rejectedMatchId != matchId) return
        controller.applyRejected(requestId, messageKey)
        rebuild()
    }

    private fun drawHeader(graphics: GuiGraphics) {
        val state = controller.state
        graphics.drawString(font, title, header().left + 8, header().top + 8, MbcGuiPalette.ACCENT_PRIMARY, false)
        val remainingSeconds = ((state.selectionDeadlineEpochMillis - System.currentTimeMillis()).coerceAtLeast(0) + 999) / 1_000
        val timer = Component.translatable(key("time_remaining"), remainingSeconds)
        graphics.drawCenteredString(font, timer, width / 2, header().top + 8, MbcGuiPalette.ACCENT_BP)
        val format = Component.translatable(key("format.${state.format.recordId}"))
        graphics.drawString(font, format, header().right - 8 - font.width(format), header().top + 8, MbcGuiPalette.TEXT_SECONDARY, false)
    }

    private fun drawTeam(
        graphics: GuiGraphics,
        panel: TowerPlayRect,
        own: Boolean,
        playerName: String,
        partialTick: Float,
    ) {
        val name = if (playerName.isBlank()) {
            if (own) minecraft?.player?.scoreboardName.orEmpty() else controller.state.opponentName
        } else {
            playerName
        }
        val clipped = font.plainSubstrByWidth(name, panel.width - 12)
        graphics.drawCenteredString(font, Component.literal(clipped), panel.left + panel.width / 2, panel.top + 7, MbcGuiPalette.TEXT_PRIMARY)
        if (own) return
        controller.state.opponentParty.take(PvpSelectionLayout.PARTY_SIZE).forEachIndexed { index, slot ->
            drawPublicSlot(graphics, panel, slot, index, index, partialTick)
        }
    }

    private fun drawPublicTeam(
        graphics: GuiGraphics,
        panel: TowerPlayRect,
        party: List<PvpSelectionOpponentSlot>,
        playerName: String,
        portraitOffset: Int,
        partialTick: Float,
    ) {
        val clipped = font.plainSubstrByWidth(playerName, panel.width - 12)
        graphics.drawCenteredString(font, Component.literal(clipped), panel.left + panel.width / 2, panel.top + 7, MbcGuiPalette.TEXT_PRIMARY)
        party.take(PvpSelectionLayout.PARTY_SIZE).forEachIndexed { index, slot ->
            drawPublicSlot(graphics, panel, slot, index, portraitOffset + index, partialTick)
        }
    }

    private fun drawPublicSlot(
        graphics: GuiGraphics,
        panel: TowerPlayRect,
        slot: PvpSelectionOpponentSlot,
        cardIndex: Int,
        portraitIndex: Int,
        partialTick: Float,
    ) {
        val card = PvpSelectionLayout.partyCard(panel, cardIndex)
        val content = PvpSelectionLayout.partyCardContent(panel, cardIndex)
        MbcGuiSurface.drawButton(graphics, card, active = false, hovered = false, selected = false, MbcGuiPalette.ACCENT_SECONDARY)
        graphics.fill(
            content.portrait.left,
            content.portrait.top,
            content.portrait.right,
            content.portrait.bottom,
            MbcGuiPalette.PANEL_ALT,
        )
        portraits.render(
            graphics,
            MbcPokemonPortraitIdentity.pvpOpponent(
                controller.state.matchId.toString(),
                portraitIndex,
                slot.speciesId,
                slot.formId,
            ),
            content.portrait,
            partialTick,
            animate = false,
        )
        val label = font.split(speciesName(slot.speciesId), (content.textRight - content.textLeft).coerceAtLeast(1))
            .firstOrNull() ?: return
        graphics.drawString(font, label, content.textLeft, content.nameTop, MbcGuiPalette.TEXT_SECONDARY, false)
    }

    private fun drawCenter(graphics: GuiGraphics) {
        val state = controller.state
        val center = centerPanel()
        graphics.drawCenteredString(font, Component.translatable(key("spectators"), state.spectators.size), width / 2, center.top + 8, MbcGuiPalette.TEXT_SECONDARY)
        val bounds = TowerPlayRect(center.left + 4, center.top + 22, center.width - 8, center.height - 26)
        val grid = PvpSpectatorGridLayout.calculate(bounds, state.spectators.map { font.width(it.name) })
        grid.slots.forEach { slot ->
            val spectator = state.spectators[slot.index]
            val skin = minecraft?.connection?.getPlayerInfo(spectator.playerId)?.skin
            if (skin != null) PlayerFaceRenderer.draw(graphics, skin, slot.face.left, slot.face.top, slot.face.width)
            graphics.drawString(
                font,
                font.plainSubstrByWidth(spectator.name, slot.nameWidth),
                slot.nameLeft,
                slot.bounds.top + 2,
                MbcGuiPalette.TEXT_PRIMARY,
                false,
            )
        }
    }

    private fun drawStatus(graphics: GuiGraphics) {
        val state = controller.state
        val status = when {
            state.spectatorMode -> Component.translatable(key("spectator_waiting"))
            controller.isPending -> Component.translatable(key("processing"))
            controller.feedbackKey != null -> Component.translatable(controller.feedbackKey!!)
            state.battleStartRetryAvailable -> Component.translatable(key("error.battle_unavailable"))
            state.waitingForOpponent -> Component.translatable(key("waiting"))
            else -> Component.translatable(
                key("selection_summary"),
                controller.selectedPokemonIds.size,
                state.format.selectionSize,
                ((state.selectionDeadlineEpochMillis - System.currentTimeMillis()).coerceAtLeast(0) + 999) / 1_000,
            )
        }
        graphics.drawCenteredString(
            font,
            status,
            width / 2,
            footer().top - 12,
            if (controller.feedbackKey == null) MbcGuiPalette.TEXT_SECONDARY else MbcGuiPalette.ACCENT_DANGER,
        )
    }

    private fun buildWidgets() {
        if (controller.state.spectatorMode) {
            addRenderableWidget(
                MbcStyledButton(footer(), Component.translatable(key("return")), MbcButtonTone.SECONDARY) {
                    leaveSpectating()
                },
            )
            return
        }
        val ownPanel = if (leftOwn()) leftPanel() else rightPanel()
        val selectionOrder = controller.selectedPokemonIds.toList()
        controller.state.ownParty.take(PvpSelectionLayout.PARTY_SIZE).forEachIndexed { index, slot ->
            val position = selectionOrder.indexOf(slot.pokemonId).takeIf { it >= 0 }?.plus(1)
            val button = PvpEntryCardButton(
                PvpSelectionLayout.partyCard(ownPanel, index),
                PvpSelectionLayout.partyCardContent(ownPanel, index),
                slot,
                position,
                speciesName(slot.speciesId),
                portraits,
            ) { if (controller.toggle(slot.pokemonId)) rebuild() }
            button.active = !controller.isPending && !controller.state.waitingForOpponent
            button.setTooltip(Tooltip.create(partyTooltip(slot)))
            addRenderableWidget(button)
        }

        val actions = split(footer(), 2)
        val state = controller.state
        val primary = when {
            state.waitingForOpponent -> MbcStyledButton(actions[0], Component.translatable(key("unready")), MbcButtonTone.SECONDARY) {
                if (controller.unready()) rebuild()
            }
            state.battleStartRetryAvailable -> MbcStyledButton(actions[0], Component.translatable(key("retry")), MbcButtonTone.PRIMARY) {
                if (controller.retry()) rebuild()
            }
            else -> MbcStyledButton(actions[0], Component.translatable(key("confirm_selection")), MbcButtonTone.PRIMARY) {
                if (controller.submit()) rebuild()
            }.also { it.active = !controller.isPending && controller.selectedPokemonIds.size == state.format.selectionSize }
        }
        addRenderableWidget(primary)
        addRenderableWidget(
            MbcStyledButton(actions[1], Component.translatable(key("cancel")), MbcButtonTone.DANGER) { confirmCancel() }
                .also { it.active = !controller.isPending },
        )
    }

    private fun confirmCancel() {
        minecraft?.setScreen(
            MbcConfirmScreen(
                this,
                Component.translatable(key("cancel.confirm.title")),
                Component.translatable(key("cancel.confirm.message")),
            ) { if (controller.cancel()) rebuild() },
        )
    }

    private fun leaveSpectating() = PvpPlayClientNetworking.exitLoungeSpectator()

    private fun rebuild() {
        clearWidgets()
        buildWidgets()
    }

    private fun leftOwn() = controller.state.playerOnLeft

    private fun partyTooltip(slot: PvpSelectionPartySlot): Component = Component.translatable(
        key("party_entry.preview_tooltip"),
        speciesName(slot.speciesId),
        slot.originalLevel,
        slot.battleLevel,
    )

    private fun speciesName(speciesId: String): Component =
        Component.translatable("cobblemon.species.${speciesId.substringAfter(':')}.name")

    private fun shell(): TowerPlayRect {
        val w = (width - 16).coerceAtMost(540)
        val h = (height - 16).coerceAtMost(320)
        return TowerPlayRect((width - w) / 2, (height - h) / 2, w, h)
    }

    private fun header() = TowerPlayRect(shell().left + 6, shell().top + 6, shell().width - 12, 27)
    private fun contentTop() = header().bottom + 5
    private fun contentHeight() = shell().height - 77
    private fun columns() = PvpSelectionLayout.columns(shell(), contentTop(), contentHeight())
    private fun leftPanel() = columns().left
    private fun centerPanel() = columns().center
    private fun rightPanel() = columns().right
    private fun footer() = TowerPlayRect(shell().left + 6, shell().bottom - 32, shell().width - 12, 20)

    private fun split(bounds: TowerPlayRect, count: Int): List<TowerPlayRect> {
        val gap = 4
        val itemWidth = (bounds.width - gap * (count - 1)) / count
        return List(count) { index -> TowerPlayRect(bounds.left + index * (itemWidth + gap), bounds.top, itemWidth, bounds.height) }
    }

    private companion object {
        fun key(path: String) = "screen.${MoreBattleContent.MOD_ID}.pvp.$path"
    }
}

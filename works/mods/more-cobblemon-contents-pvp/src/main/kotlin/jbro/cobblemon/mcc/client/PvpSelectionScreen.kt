package jbro.cobblemon.mcc.client

import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.stats.Stat
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.api.types.ElementalType
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.client.gui.TypeIcon
import com.cobblemon.mod.common.pokemon.Pokemon
import com.mojang.blaze3d.systems.RenderSystem
import java.util.UUID
import jbro.cobblemon.mcc.client.hub.MccHubKit
import jbro.cobblemon.mcc.client.hub.MccPokemonPortraits
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomMemberView
import jbro.cobblemon.mcc.internal.pvp.ui.PvpSelectionOpponentSlot
import jbro.cobblemon.mcc.internal.pvp.ui.PvpSelectionPartySlot
import jbro.cobblemon.mcc.internal.pvp.ui.PvpSelectionScreenController
import jbro.cobblemon.ui.extended.pokemon.render.PokemonModelRenderer
import jbro.cobblemon.ui.extended.pokemon.tooltip.TooltipDataBuilder
import jbro.cobblemon.ui.extended.ui.shared.BattleControlRenderer
import jbro.cobblemon.ui.extended.ui.shared.BattleCornerCuts
import jbro.cobblemon.ui.extended.ui.shared.BattleFocusMotion
import jbro.cobblemon.ui.extended.ui.shared.BattleGenderText
import jbro.cobblemon.ui.extended.ui.shared.BattleSurface
import jbro.cobblemon.ui.extended.ui.shared.BattleSurfaceRenderer
import jbro.cobblemon.ui.extended.ui.shared.BattleUiTheme
import jbro.cobblemon.uikit.UiRect
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import org.lwjgl.glfw.GLFW

/**
 * The team preview as its own screen over the PvP arena, drawn in the battle UI's theme: the viewer's team down the
 * left, the opponent's public team down the right, and between them a frame describing the Pokemon under the cursor
 * (or, with nothing under it, the one picked last). Mouse and keyboard both move the cursor. A spectator sees both
 * teams' public information only.
 */
internal class PvpSelectionScreen(private val controller: PvpSelectionScreenController) :
    Screen(Component.translatable("screen.more_cobblemon_contents.pvp.title")) {

    private enum class Zone { LEFT, RIGHT, BUTTONS }

    /** Where the keyboard cursor sits; the mouse takes over whenever it moves. */
    private var zone = Zone.LEFT
    private var index = 0
    private var mouseDriven = true
    private var lastMouseX = -1.0
    private var lastMouseY = -1.0
    /** The own Pokemon picked or unpicked last, which the frame shows when the cursor rests on nothing. */
    private var lastPicked: UUID? = null

    private val state get() = controller.state
    private val spectator get() = state.spectatorMode

    override fun isPauseScreen(): Boolean = false

    /** The hotbar and hearts would show through the bottom bar; they hide while the preview is up, as with F1. */
    private var hudWasHidden: Boolean? = null

    override fun init() {
        val options = Minecraft.getInstance().options
        if (hudWasHidden == null) hudWasHidden = options.hideGui
        options.hideGui = true
    }

    override fun removed() {
        hudWasHidden?.let { Minecraft.getInstance().options.hideGui = it }
        hudWasHidden = null
    }

    override fun tick() {
        // The server ended or replaced this preview: leave without a trace.
        if (PvpHubClient.selection !== controller) onClose()
    }

    override fun onClose() {
        Minecraft.getInstance().setScreen(null)
    }

    // ---- layout ----

    private class Layout(
        val top: UiRect,
        val left: UiRect,
        val leftHeader: UiRect,
        val leftCards: List<UiRect>,
        val right: UiRect,
        val rightHeader: UiRect,
        val rightCards: List<UiRect>,
        val center: UiRect,
        val bottom: UiRect,
        val buttons: List<UiRect>,
    )

    private fun layout(): Layout {
        val margin = if (width < 520) 6 else 10
        val gap = if (width < 520) 5 else 8
        val top = UiRect(margin, margin, width - margin * 2, TOP_HEIGHT)
        val bottom = UiRect(margin, height - margin - BOTTOM_HEIGHT, width - margin * 2, BOTTOM_HEIGHT)
        val bodyY = top.bottom + gap
        val bodyHeight = bottom.y - gap - bodyY
        val sideWidth = (width * .29f).toInt().coerceIn(118, 176)
        val opponentWidth = (width * .24f).toInt().coerceIn(104, 150)
        val left = UiRect(margin, bodyY, sideWidth, bodyHeight)
        val right = UiRect(width - margin - opponentWidth, bodyY, opponentWidth, bodyHeight)
        val center = UiRect(left.right + gap, bodyY, right.x - gap - left.right - gap, bodyHeight)
        fun cards(column: UiRect, header: UiRect): List<UiRect> {
            val areaTop = header.bottom + 3
            val cardHeight = ((column.bottom - areaTop - CARD_GAP * 5) / 6).coerceIn(16, 32)
            return List(TEAM_SIZE) { UiRect(column.x, areaTop + it * (cardHeight + CARD_GAP), column.width, cardHeight) }
        }
        val leftHeader = UiRect(left.x, left.y, left.width, HEADER_HEIGHT)
        val rightHeader = UiRect(right.x, right.y, right.width, HEADER_HEIGHT)
        val buttonWidth = (bottom.width / 5).coerceIn(80, 120)
        val buttons = listOf(
            UiRect(bottom.x, bottom.y + 2, buttonWidth, bottom.height - 4),
            UiRect(bottom.right - buttonWidth, bottom.y + 2, buttonWidth, bottom.height - 4),
        )
        return Layout(top, left, leftHeader, cards(left, leftHeader), right, rightHeader, cards(right, rightHeader), center, bottom, buttons)
    }

    // ---- what each side shows ----

    private val leftName get() = state.leftPlayerName.ifBlank { if (state.playerOnLeft) ownName() else state.opponentName }
    private val rightName get() = state.rightPlayerName.ifBlank { if (state.playerOnLeft) state.opponentName else ownName() }
    private fun ownName() = Minecraft.getInstance().player?.scoreboardName.orEmpty()

    /** The viewer's own team goes left; a spectator sees the room's left and right players where they sit. */
    private val leftParty: List<PvpSelectionPartySlot> get() = if (spectator) emptyList() else state.ownParty.take(TEAM_SIZE)
    private val rightPublic: List<PvpSelectionOpponentSlot>
        get() = (if (spectator) state.spectatorRightParty else state.opponentParty).take(TEAM_SIZE)
    private val leftPublic: List<PvpSelectionOpponentSlot> get() = state.spectatorLeftParty.take(TEAM_SIZE)
    private val leftCount get() = if (spectator) leftPublic.size else leftParty.size
    private val ownTitle get() = if (spectator) leftName else ownName().ifBlank { leftName }
    private val opponentTitle get() = if (spectator) rightName else state.opponentName.ifBlank { rightName }

    private fun clientPokemon(id: UUID): Pokemon? = CobblemonClient.storage.party.firstOrNull { it?.uuid == id }

    // ---- input ----

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        if (mouseX != lastMouseX || mouseY != lastMouseY) mouseDriven = true
        lastMouseX = mouseX
        lastMouseY = mouseY
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button)
        mouseDriven = true
        val layout = layout()
        val x = mouseX.toInt()
        val y = mouseY.toInt()
        layout.leftCards.take(leftCount).forEachIndexed { i, rect ->
            if (rect.contains(x, y)) {
                pick(i)
                return true
            }
        }
        layout.buttons.forEachIndexed { i, rect -> if (rect.contains(x, y) && press(i)) return true }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val step = when (keyCode) {
            GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> 0 to -1
            GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> 0 to 1
            GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> -1 to 0
            GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> 1 to 0
            else -> null
        }
        if (step != null) {
            // The first press after using the mouse shows the cursor where it already is.
            if (mouseDriven) mouseDriven = false else move(step.first, step.second)
            return true
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_SPACE) {
            mouseDriven = false
            when (zone) {
                Zone.LEFT -> pick(index)
                Zone.BUTTONS -> press(index)
                Zone.RIGHT -> Unit
            }
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    /** Moves the keyboard cursor between the two teams and, below the last card, the buttons. */
    private fun move(dx: Int, dy: Int) {
        val rightCount = rightPublic.size
        val buttons = if (spectator) 1 else 2
        when (zone) {
            Zone.LEFT, Zone.RIGHT -> {
                val count = if (zone == Zone.LEFT) leftCount else rightCount
                when {
                    dx > 0 && zone == Zone.LEFT && rightCount > 0 -> { zone = Zone.RIGHT; index = index.coerceAtMost(rightCount - 1) }
                    dx < 0 && zone == Zone.RIGHT && leftCount > 0 -> { zone = Zone.LEFT; index = index.coerceAtMost(leftCount - 1) }
                    dy > 0 && index >= count - 1 -> { zone = Zone.BUTTONS; index = buttons - 1 }
                    dy != 0 -> index = (index + dy).coerceIn(0, (count - 1).coerceAtLeast(0))
                }
            }
            Zone.BUTTONS -> when {
                dx != 0 -> index = (index + dx).coerceIn(0, buttons - 1)
                dy < 0 -> { zone = Zone.LEFT; index = (leftCount - 1).coerceAtLeast(0) }
            }
        }
    }

    private fun pick(slot: Int) {
        if (spectator) return
        val pokemon = leftParty.getOrNull(slot) ?: return
        if (controller.toggle(pokemon.pokemonId)) lastPicked = pokemon.pokemonId
        zone = Zone.LEFT
        index = slot
    }

    /** Presses bottom button [which] (0 the left one, 1 the right one); false when it does nothing now. */
    private fun press(which: Int): Boolean {
        if (spectator) {
            if (which != 0) return false
            PvpPlayClientNetworking.exitLoungeSpectator()
            return true
        }
        if (which == 0) {
            if (controller.isPending) return false
            MccHubKit.confirm(pvp("cancel.confirm.title"), pvp("cancel.confirm.message"), pvp("cancel"), Component.translatable("gui.back")) {
                controller.cancel()
            }
            return true
        }
        return when {
            state.battleStartRetryAvailable -> controller.retry()
            state.waitingForOpponent -> controller.unready()
            else -> controller.submit()
        }
    }

    private fun primaryLabel(): Component = when {
        spectator -> Component.empty()
        state.battleStartRetryAvailable -> pvp("retry")
        state.waitingForOpponent -> pvp("unready")
        else -> pvp("confirm_selection")
    }

    private fun primaryEnabled(): Boolean = !controller.isPending && when {
        state.battleStartRetryAvailable -> true
        state.waitingForOpponent -> true
        else -> controller.selectedPokemonIds.size == state.requiredSelectionSize
    }

    // ---- drawing ----

    override fun renderBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // The arena stays in view: only the bands the panels sit on darken, the middle just a little.
        graphics.fillGradient(0, 0, width, height / 3, 0xB0050A14.toInt(), 0x40050A14)
        graphics.fill(0, height / 3, width, height * 2 / 3, 0x40050A14)
        graphics.fillGradient(0, height * 2 / 3, width, height, 0x40050A14, 0xC0050A14.toInt())
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics, mouseX, mouseY, partialTick)
        val layout = layout()
        val hovered = if (mouseDriven) hoverAt(layout, mouseX, mouseY) else null
        val focus: Pair<Zone, Int>? = if (mouseDriven) hovered else zone to index
        drawTop(graphics, layout.top, mouseX, mouseY)
        drawColumnHeader(graphics, layout.leftHeader, ownTitle, left = true)
        drawColumnHeader(graphics, layout.rightHeader, opponentTitle, left = false)
        val order = controller.selectedPokemonIds.toList()
        if (spectator) {
            leftPublic.forEachIndexed { i, slot -> drawPublicCard(graphics, layout.leftCards[i], slot, i, left = true, focus == Zone.LEFT to i) }
        } else {
            leftParty.forEachIndexed { i, slot ->
                drawOwnCard(graphics, layout.leftCards[i], slot, order.indexOf(slot.pokemonId), focus == Zone.LEFT to i)
            }
        }
        rightPublic.forEachIndexed { i, slot -> drawPublicCard(graphics, layout.rightCards[i], slot, i + TEAM_SIZE, left = false, focus == Zone.RIGHT to i) }
        focus?.let { (z, i) ->
            val rect = when (z) {
                Zone.LEFT -> layout.leftCards.getOrNull(i)
                Zone.RIGHT -> layout.rightCards.getOrNull(i)
                Zone.BUTTONS -> null
            }
            rect?.let { BattleControlRenderer.drawCursor(graphics, CURSOR, it.x - 2, it.y + it.height / 2f, BattleUiTheme.palette.select, 1f) }
        }
        drawCenter(graphics, layout.center, focus)
        drawBottom(graphics, layout, focus)
        hoveredSpectator(layout.top, mouseX, mouseY)?.let { PvpSpectatorCard.drawOne(graphics, mouseX, mouseY, it) }
    }

    /** The middle of card [index] in the right (or left) column, for the capture harness to hover. */
    internal fun cardCenter(right: Boolean, index: Int): Pair<Int, Int> {
        val rect = (if (right) layout().rightCards else layout().leftCards)[index]
        return rect.x + rect.width / 2 to rect.y + rect.height / 2
    }

    private fun hoverAt(layout: Layout, mouseX: Int, mouseY: Int): Pair<Zone, Int>? {
        layout.leftCards.take(leftCount).forEachIndexed { i, rect -> if (rect.contains(mouseX, mouseY)) return Zone.LEFT to i }
        layout.rightCards.take(rightPublic.size).forEachIndexed { i, rect -> if (rect.contains(mouseX, mouseY)) return Zone.RIGHT to i }
        layout.buttons.forEachIndexed { i, rect -> if (rect.contains(mouseX, mouseY)) return Zone.BUTTONS to i }
        return null
    }

    private fun drawTop(graphics: GuiGraphics, rect: UiRect, mouseX: Int, mouseY: Int) {
        val palette = BattleUiTheme.palette
        BattleSurfaceRenderer.draw(graphics, rect.x, rect.y, rect.width, rect.height,
            BattleUiTheme.shell.copy(top = palette.headerTop, bottom = palette.headerBottom, borderWidth = 0,
                cornerCuts = BattleCornerCuts(6, 6, 6, 6)))
        // The clock: a pill in the focus colour that turns to danger in the last ten seconds.
        val seconds = remainingSeconds()
        val clock = "%d:%02d".format(seconds / 60, seconds % 60)
        val clockWidth = font.width(clock) + 14
        BattleSurfaceRenderer.capsule(graphics, rect.x + 3, rect.y + 3, clockWidth, rect.height - 6,
            if (seconds <= 10) BattleUiTheme.DANGER else BattleUiTheme.FOCUS)
        graphics.drawString(font, clock, rect.x + 3 + 7, rect.y + (rect.height - 8) / 2, palette.primaryText, false)
        val title = Component.empty().append(pvp("title")).append(" · ").append(pvp("format.${state.format.recordId}"))
        graphics.drawString(font, title, rect.x + clockWidth + 10, rect.y + (rect.height - 8) / 2, BattleUiTheme.TEXT, false)
        // Spectators' faces from the right edge; hovering one names them.
        spectatorFaces(rect).forEach { (member, face) -> PvpSpectatorCard.face(graphics, member, face.x, face.y, face.width) }
        val faces = spectatorFaces(rect)
        if (state.spectators.size > faces.size) {
            val more = "+${state.spectators.size - faces.size}"
            val left = faces.lastOrNull()?.second?.x ?: (rect.right - 4)
            graphics.drawString(font, more, left - 3 - font.width(more), rect.y + (rect.height - 8) / 2, BattleUiTheme.MUTED, false)
        }
    }

    private fun spectatorFaces(rect: UiRect): List<Pair<PvpRoomMemberView, UiRect>> {
        val size = rect.height - 8
        val shown = state.spectators.take(MAX_FACES)
        return shown.mapIndexed { i, s ->
            PvpRoomMemberView(s.playerId, s.name) to UiRect(rect.right - 5 - (i + 1) * (size + 2) + 2, rect.y + 4, size, size)
        }
    }

    private fun hoveredSpectator(rect: UiRect, mouseX: Int, mouseY: Int): PvpRoomMemberView? =
        spectatorFaces(rect).firstOrNull { it.second.contains(mouseX, mouseY) }?.first

    private fun drawColumnHeader(graphics: GuiGraphics, rect: UiRect, name: String, left: Boolean) {
        val palette = BattleUiTheme.palette
        val top = if (left) palette.headerTop else palette.opponentHeaderTop
        val bottom = if (left) palette.headerBottom else palette.opponentHeaderBottom
        BattleSurfaceRenderer.draw(graphics, rect.x, rect.y, rect.width, rect.height,
            BattleUiTheme.shell.copy(top = top, bottom = bottom, borderWidth = 0, cornerCuts = BattleCornerCuts(5, 5, 5, 5)))
        graphics.fill(rect.x + 4, rect.bottom - 2, rect.right - 4, rect.bottom - 1, if (left) BattleUiTheme.CYAN else BattleUiTheme.PURPLE)
        graphics.drawString(font, font.plainSubstrByWidth(name, rect.width - 10), rect.x + 6, rect.y + (rect.height - 8) / 2,
            if (left) BattleUiTheme.CYAN else BattleUiTheme.readable(BattleUiTheme.palette.transcriptOpponentText), false)
    }

    private fun drawOwnCard(graphics: GuiGraphics, rect: UiRect, slot: PvpSelectionPartySlot, order: Int, focused: Boolean) {
        val palette = BattleUiTheme.palette
        val picked = order >= 0
        val corners = BattleCornerCuts(5, 5, 5, 5)
        val emphasis = BattleFocusMotion.emphasis(slot.pokemonId to "pvp-select", focused)
        if (palette.focusFill == null) BattleControlRenderer.drawFocusHalo(graphics, rect.x, rect.y, rect.width, rect.height, corners,
            palette.select, emphasis, 1f)
        val restTop = if (picked) palette.select else palette.cardTop
        val restBottom = if (picked) palette.selectBottom else palette.cardBottom
        BattleSurfaceRenderer.draw(graphics, rect.x, rect.y, rect.width, rect.height,
            BattleUiTheme.panel.copy(top = BattleSurfaceRenderer.interpolate(restTop, palette.activeCardTop, if (picked) 0f else emphasis * .6f),
                bottom = BattleSurfaceRenderer.interpolate(restBottom, palette.activeCardBottom, if (picked) 0f else emphasis * .6f),
                borderWidth = 0, cornerCuts = corners))
        val ink = if (picked) palette.selectInk else BattleUiTheme.TEXT
        val muted = if (picked) palette.selectInk else BattleUiTheme.MUTED
        val pokemon = clientPokemon(slot.pokemonId)
        val model = rect.height + 4
        drawCardModel(graphics, rect, rect.x + 2, model, ResourceLocation.tryParse(slot.speciesId),
            pokemon?.aspects ?: publicAspects(slot.speciesId, slot.formId), slot.pokemonId, true)
        val textX = rect.x + model + 6
        val badge = if (picked) 12 else 0
        val level = "Lv.${slot.battleLevel}"
        val name = pokemon?.getDisplayName()?.string ?: speciesName(slot.speciesId).string
        val twoLines = rect.height >= 24
        val nameY = if (twoLines) rect.y + 4 else rect.y + (rect.height - 8) / 2
        BattleGenderText.draw(graphics, name, pokemon?.gender, textX, nameY, rect.right - textX - font.width(level) - 8 - badge, 1f, ink)
        graphics.drawString(font, level, rect.right - 5 - badge - font.width(level), nameY, muted, false)
        if (twoLines) {
            val item = pokemon?.heldItem() ?: ItemStack.EMPTY
            if (!item.isEmpty) {
                drawItem(graphics, item, textX, rect.y + rect.height - 12, 9)
                graphics.drawString(font, font.plainSubstrByWidth(item.hoverName.string, rect.right - textX - 16 - badge),
                    textX + 11, rect.y + rect.height - 11, muted, false)
            }
        }
        if (picked) {
            // The pick order on a dark disc at the card's end.
            val size = 11
            val x = rect.right - size - 3
            val y = rect.y + (rect.height - size) / 2
            BattleSurfaceRenderer.capsule(graphics, x, y, size, size, palette.selectInk)
            val number = (order + 1).toString()
            graphics.drawString(font, number, x + (size - font.width(number)) / 2 + 1, y + 2, palette.select, false)
        }
    }

    private fun drawPublicCard(graphics: GuiGraphics, rect: UiRect, slot: PvpSelectionOpponentSlot, key: Int, left: Boolean, focused: Boolean) {
        val palette = BattleUiTheme.palette
        val corners = BattleCornerCuts(5, 5, 5, 5)
        val stateKey = "pvp-public:${state.matchId}:$key"
        val emphasis = BattleFocusMotion.emphasis(stateKey, focused)
        val accent = if (left) BattleUiTheme.CYAN else BattleUiTheme.PURPLE
        if (palette.focusFill == null) BattleControlRenderer.drawFocusHalo(graphics, rect.x, rect.y, rect.width, rect.height, corners,
            accent, emphasis, 1f)
        val top = if (left) palette.cardTop else palette.opponentRowTop
        val bottom = if (left) palette.cardBottom else palette.opponentRowBottom
        BattleSurfaceRenderer.draw(graphics, rect.x, rect.y, rect.width, rect.height,
            BattleUiTheme.panel.copy(top = BattleSurfaceRenderer.interpolate(top, palette.hudFocusTop, emphasis * .5f), bottom = bottom,
                borderWidth = 0, cornerCuts = corners))
        val model = rect.height + 4
        val aspects = publicAspects(slot.speciesId, slot.formId)
        val modelX = if (left) rect.x + 2 else rect.right - model - 2
        drawCardModel(graphics, rect, modelX, model, ResourceLocation.tryParse(slot.speciesId), aspects, uuidFor(stateKey), left)
        val textX = if (left) rect.x + model + 6 else rect.x + 6
        val textRight = if (left) rect.right - 4 else modelX - 3
        val twoLines = rect.height >= 24
        val nameY = if (twoLines) rect.y + 4 else rect.y + (rect.height - 8) / 2
        graphics.drawString(font, font.plainSubstrByWidth(speciesName(slot.speciesId).string, textRight - textX), textX, nameY, BattleUiTheme.TEXT, false)
        val types = publicTypes(slot.speciesId, aspects)
        if (twoLines) drawTypeIcons(graphics, types, textX, rect.y + rect.height - 12)
    }

    private fun drawCenter(graphics: GuiGraphics, rect: UiRect, focus: Pair<Zone, Int>?) {
        val palette = BattleUiTheme.palette
        BattleSurfaceRenderer.draw(graphics, rect.x, rect.y, rect.width, rect.height,
            BattleUiTheme.panel.copy(top = palette.detailTop, bottom = palette.detailBottom, borderWidth = 0,
                cornerCuts = BattleCornerCuts(8, 8, 8, 8)))
        val instruction = if (spectator) pvp("select.instruction_spectator") else pvp("select.instruction", state.requiredSelectionSize)
        val header = UiRect(rect.x, rect.y, rect.width, HEADER_HEIGHT)
        BattleSurfaceRenderer.draw(graphics, header.x, header.y, header.width, header.height,
            BattleUiTheme.shell.copy(top = palette.detailHeaderTop, bottom = palette.detailHeaderBottom, borderWidth = 0,
                cornerCuts = BattleCornerCuts(topLeft = 8, topRight = 8)))
        graphics.drawString(font, MccHubKit.fitted(instruction, header.width - 12), header.x + 6, header.y + (header.height - 8) / 2,
            BattleUiTheme.TEXT, false)
        val body = UiRect(rect.x + 6, header.bottom + 5, rect.width - 12, rect.bottom - header.bottom - 10)
        // Under the cursor first; else the Pokemon picked last; else the first of the viewer's team.
        when {
            focus?.first == Zone.LEFT && !spectator -> leftParty.getOrNull(focus.second)?.let { drawOwnDetail(graphics, body, it) }
            focus?.first == Zone.LEFT -> leftPublic.getOrNull(focus.second)?.let { drawPublicDetail(graphics, body, it, "l${focus.second}", true) }
            focus?.first == Zone.RIGHT -> rightPublic.getOrNull(focus.second)?.let { drawPublicDetail(graphics, body, it, "r${focus.second}", false) }
            !spectator -> (leftParty.firstOrNull { it.pokemonId == lastPicked }
                ?: controller.selectedPokemonIds.lastOrNull()?.let { id -> leftParty.firstOrNull { it.pokemonId == id } }
                ?: leftParty.firstOrNull())?.let { drawOwnDetail(graphics, body, it) }
            else -> leftPublic.firstOrNull()?.let { drawPublicDetail(graphics, body, it, "l0", true) }
        }
    }

    private fun drawOwnDetail(graphics: GuiGraphics, rect: UiRect, slot: PvpSelectionPartySlot) {
        val palette = BattleUiTheme.palette
        val pokemon = clientPokemon(slot.pokemonId)
        val portrait = (rect.height / 3).coerceIn(36, 64)
        BattleSurfaceRenderer.draw(graphics, rect.x, rect.y, portrait, portrait,
            BattleUiTheme.panel.copy(top = palette.portraitTop, bottom = palette.portraitBottom, borderWidth = 0,
                cornerCuts = BattleCornerCuts(6, 6, 6, 6)))
        PokemonModelRenderer.drawPokemonModel(graphics, rect.x - portrait / 8, rect.y, portrait + portrait / 4, null,
            ResourceLocation.tryParse(slot.speciesId), pokemon?.aspects ?: emptySet(), slot.pokemonId, false, null, true, { it }, 1f)
        val infoX = rect.x + portrait + 6
        val infoWidth = rect.right - infoX
        val level = "Lv.${slot.battleLevel}"
        BattleGenderText.draw(graphics, pokemon?.getDisplayName()?.string ?: speciesName(slot.speciesId).string, pokemon?.gender,
            infoX, rect.y + 2, infoWidth - font.width(level) - 4, 1f)
        graphics.drawString(font, level, rect.right - font.width(level), rect.y + 2, BattleUiTheme.MUTED, false)
        var lineY = rect.y + 14
        val types = pokemon?.let { listOfNotNull(it.form.primaryType, it.form.secondaryType) }
            ?: publicTypes(slot.speciesId, emptySet())
        drawTypeRow(graphics, types, infoX, lineY)
        lineY += 12
        val labelWidth = listOf("select.detail.ability", "select.detail.item", "select.detail.nature").maxOf { font.width(pvp(it)) } + 6
        fun row(labelKey: String, value: String, icon: ItemStack = ItemStack.EMPTY) {
            if (lineY + 9 > rect.y + portrait + 14 && lineY + 9 > rect.bottom) return
            graphics.drawString(font, pvp(labelKey), infoX, lineY, BattleUiTheme.CYAN, false)
            var valueX = infoX + labelWidth
            if (!icon.isEmpty) {
                drawItem(graphics, icon, valueX, lineY - 1, 9)
                valueX += 11
            }
            graphics.drawString(font, font.plainSubstrByWidth(value, rect.right - valueX), valueX, lineY, BattleUiTheme.TEXT, false)
            lineY += 11
        }
        if (pokemon != null) {
            row("select.detail.ability", TooltipDataBuilder.formatAbilityName(pokemon.ability.name))
            val item = pokemon.heldItem()
            row("select.detail.item", if (item.isEmpty) pvp("select.detail.none").string else item.hoverName.string, item)
            row("select.detail.nature", Component.translatable(pokemon.effectiveNature.displayName).string)
        }
        val below = maxOf(rect.y + portrait, lineY) + 5
        if (pokemon == null) return
        val area = UiRect(rect.x, below, rect.width, rect.bottom - below)
        // Side by side from the width a small window gives; below it the stats go under the moves when they fit.
        val wide = area.width >= 180
        val movesRect = if (wide) UiRect(area.x, area.y, area.width / 2 - 3, area.height) else area
        val movesBottom = drawMoves(graphics, movesRect, pokemon)
        val statsRect = if (wide) UiRect(area.x + area.width / 2 + 3, area.y, area.width - area.width / 2 - 3, area.height)
            else UiRect(area.x, movesBottom + 5, area.width, area.bottom - movesBottom - 5)
        if (statsRect.height >= 12 + 6 * STAT_ROW) drawStats(graphics, statsRect, pokemon, slot.battleLevel)
    }

    /** The four moves under a band; returns the y below the last. */
    private fun drawMoves(graphics: GuiGraphics, rect: UiRect, pokemon: Pokemon): Int {
        val palette = BattleUiTheme.palette
        BattleSurfaceRenderer.draw(graphics, rect.x, rect.y, rect.width, 12, BattleSurface(palette.movesBand, cut = 4))
        graphics.drawString(font, pvp("select.detail.moves"), rect.x + 4, rect.y + 2, BattleUiTheme.CYAN, false)
        var y = rect.y + 14
        pokemon.moveSet.getMoves().take(4).forEachIndexed { i, move ->
            if (y + 10 > rect.bottom) return y
            if (i % 2 == 0) BattleSurfaceRenderer.draw(graphics, rect.x, y - 1, rect.width, 11, BattleSurface(palette.zebra, cut = 3))
            Moves.getByName(move.name)?.elementalType?.let { TypeIcon(x = (rect.x + 8).toFloat(), y = y.toFloat(), type = it, small = true).render(graphics) }
            val pp = "${move.maxPp}"
            graphics.drawString(font, font.plainSubstrByWidth(move.displayName.string, rect.width - 26 - font.width(pp)), rect.x + 19, y,
                BattleUiTheme.TEXT, false)
            graphics.drawString(font, pp, rect.right - 4 - font.width(pp), y, BattleUiTheme.MUTED, false)
            y += 11
        }
        return y
    }

    /** The six stats at the battle level, with nature-raised and lowered ones tinted, each with a short bar. */
    private fun drawStats(graphics: GuiGraphics, rect: UiRect, pokemon: Pokemon, level: Int) {
        val palette = BattleUiTheme.palette
        BattleSurfaceRenderer.draw(graphics, rect.x, rect.y, rect.width, 12, BattleSurface(palette.movesBand, cut = 4))
        graphics.drawString(font, pvp("select.detail.stats", level), rect.x + 4, rect.y + 2, BattleUiTheme.CYAN, false)
        val nature = pokemon.effectiveNature
        val labelWidth = STATS.maxOf { font.width(it.displayName) } + 6
        var y = rect.y + 14
        STATS.forEach { stat ->
            val value = battleStat(pokemon, stat, level)
            val color = when (stat) {
                nature.increasedStat -> BattleUiTheme.DANGER
                nature.decreasedStat -> BattleUiTheme.MALE
                else -> BattleUiTheme.TEXT
            }
            graphics.drawString(font, stat.displayName, rect.x + 4, y, color, false)
            val number = value.toString()
            val barX = rect.x + 4 + labelWidth
            val numberX = rect.right - 4 - font.width("999")
            graphics.drawString(font, number, rect.right - 4 - font.width(number), y, BattleUiTheme.TEXT, false)
            val barWidth = numberX - 4 - barX
            if (barWidth > 8) BattleSurfaceRenderer.gauge(graphics, barX, y + 3, barWidth, 3, (value / 400f).coerceIn(0f, 1f),
                BattleUiTheme.TRACK, if (stat == Stats.HP) BattleUiTheme.GOOD else BattleUiTheme.CYAN, 1f, inset = 0)
            y += STAT_ROW
        }
    }

    private fun drawPublicDetail(graphics: GuiGraphics, rect: UiRect, slot: PvpSelectionOpponentSlot, key: String, left: Boolean) {
        val palette = BattleUiTheme.palette
        val aspects = publicAspects(slot.speciesId, slot.formId)
        val portrait = (minOf(rect.width, rect.height) * 2 / 3).coerceIn(48, 110)
        val portraitX = rect.x + (rect.width - portrait) / 2
        BattleSurfaceRenderer.draw(graphics, portraitX, rect.y, portrait, portrait,
            BattleUiTheme.panel.copy(top = palette.portraitTop, bottom = palette.portraitBottom, borderWidth = 0,
                cornerCuts = BattleCornerCuts(8, 8, 8, 8)))
        PokemonModelRenderer.drawPokemonModel(graphics, portraitX - portrait / 8, rect.y, portrait + portrait / 4, null,
            ResourceLocation.tryParse(slot.speciesId), aspects, uuidFor("pvp-detail:${state.matchId}:$key"), false, null, left, { it }, 1f)
        var y = rect.y + portrait + 6
        val name = speciesName(slot.speciesId)
        graphics.drawString(font, name, rect.x + (rect.width - font.width(name)) / 2, y, BattleUiTheme.TEXT, false)
        y += 12
        val form = formName(slot.speciesId, slot.formId)
        val formLine = Component.empty().append(pvp("select.detail.form")).append(" ").append(form)
        graphics.drawString(font, formLine, rect.x + (rect.width - font.width(formLine)) / 2, y, BattleUiTheme.MUTED, false)
        y += 12
        val types = publicTypes(slot.speciesId, aspects)
        val typesWidth = types.sumOf { 12 + font.width(it.displayName) + 6 } - 6
        drawTypeRow(graphics, types, rect.x + (rect.width - typesWidth) / 2, y)
        y += 14
        val note = pvp("select.detail.public")
        if (y + 9 <= rect.bottom) graphics.drawString(font, MccHubKit.fitted(note, rect.width), rect.x + (rect.width - font.width(MccHubKit.fitted(note, rect.width))) / 2, y,
            BattleUiTheme.DIM, false)
    }

    private fun drawBottom(graphics: GuiGraphics, layout: Layout, focus: Pair<Zone, Int>?) {
        val palette = BattleUiTheme.palette
        val rect = layout.bottom
        val (cancelRect, primaryRect) = layout.buttons
        val cancelLabel = if (spectator) pvp("return") else pvp("cancel")
        drawButton(graphics, cancelRect, cancelLabel, if (spectator) BattleUiTheme.secondary else BattleUiTheme.danger,
            BattleUiTheme.TEXT, !controller.isPending, focus == Zone.BUTTONS to 0)
        if (!spectator) drawButton(graphics, primaryRect, primaryLabel(), BattleUiTheme.primary, palette.primaryText, primaryEnabled(),
            focus == Zone.BUTTONS to 1)
        // Between the buttons: how far the pick is, or what the server said.
        val status = statusLine()
        val middleX = cancelRect.right + 8
        val middleWidth = (if (spectator) rect.right else primaryRect.x - 8) - middleX
        val statusColor = if (controller.feedbackKey != null && !controller.isPending) BattleUiTheme.DANGER else BattleUiTheme.TEXT
        // A spectator already reads what is happening in the frame's header.
        if (!spectator) {
            val line = MccHubKit.fitted(status, middleWidth)
            graphics.drawString(font, line, middleX + (middleWidth - font.width(line)) / 2, rect.y + 4, statusColor, true)
        }
        val hint = MccHubKit.fitted(pvp("select.keys"), middleWidth)
        graphics.drawString(font, hint, middleX + (middleWidth - font.width(hint)) / 2, rect.y + 15, BattleUiTheme.MUTED, true)
    }

    private fun drawButton(graphics: GuiGraphics, rect: UiRect, label: Component, surface: BattleSurface, text: Int, enabled: Boolean, focused: Boolean) {
        val emphasis = BattleFocusMotion.emphasis(rect.x to "pvp-select-button", focused && enabled)
        val corners = BattleCornerCuts(6, 6, 6, 6)
        BattleControlRenderer.drawFocusHalo(graphics, rect.x, rect.y, rect.width, rect.height, corners, BattleUiTheme.palette.select, emphasis, 1f)
        BattleSurfaceRenderer.draw(graphics, rect.x, rect.y, rect.width, rect.height, surface.copy(cornerCuts = corners), if (enabled) 1f else .45f)
        val fitted = MccHubKit.fitted(label, rect.width - 8)
        graphics.drawString(font, fitted, rect.x + (rect.width - font.width(fitted)) / 2, rect.y + (rect.height - 8) / 2,
            if (enabled) text else BattleUiTheme.DIM, false)
    }

    private fun statusLine(): Component = when {
        controller.isPending -> pvp("processing")
        controller.feedbackKey != null -> Component.translatable(controller.feedbackKey!!)
        spectator -> pvp("select.instruction_spectator")
        state.battleStartRetryAvailable -> pvp("error.battle_unavailable")
        state.waitingForOpponent -> pvp("waiting")
        else -> pvp("select.count", controller.selectedPokemonIds.size, state.requiredSelectionSize)
    }

    // ---- small helpers ----

    /**
     * A card's Pokemon in a square [size] wide at [x]: drawn half again as large as the square and cut to the card,
     * since the battle UI's model framing leaves most small Pokemon tiny at card height.
     */
    private fun drawCardModel(graphics: GuiGraphics, card: UiRect, x: Int, size: Int, species: ResourceLocation?,
                              aspects: Set<String>, uuid: UUID, facingRight: Boolean) {
        val drawn = size * 3 / 2
        graphics.enableScissor(maxOf(card.x, x), card.y + 1, minOf(card.right, x + size), card.bottom - 1)
        try {
            PokemonModelRenderer.drawPokemonModel(graphics, x - (drawn - size) / 2, card.y - drawn / 6, drawn, null, species,
                aspects, uuid, false, null, facingRight, { it }, 1f)
        } finally {
            graphics.disableScissor()
        }
    }

    private fun drawTypeIcons(graphics: GuiGraphics, types: List<ElementalType>, x: Int, y: Int) {
        types.forEachIndexed { i, type -> TypeIcon(x = x + i * 11, y = y, type = type, small = true).render(graphics) }
    }

    private fun drawTypeRow(graphics: GuiGraphics, types: List<ElementalType>, x: Int, y: Int) {
        var typeX = x
        types.forEach { type ->
            TypeIcon(x = typeX.toFloat(), y = y.toFloat(), type = type, small = true).render(graphics)
            graphics.drawString(font, type.displayName, typeX + 12, y, BattleUiTheme.MUTED, false)
            typeX += 12 + font.width(type.displayName) + 6
        }
    }

    private fun drawItem(graphics: GuiGraphics, item: ItemStack, x: Int, y: Int, size: Int) {
        graphics.flush()
        graphics.pose().pushPose()
        try {
            graphics.pose().translate(x.toFloat(), y.toFloat(), 0f)
            graphics.pose().scale(size / 16f, size / 16f, 1f)
            RenderSystem.enableBlend()
            graphics.renderItem(item, 0, 0)
            graphics.flush()
        } finally {
            graphics.pose().popPose()
        }
    }

    private fun publicAspects(speciesId: String, formId: String?): Set<String> =
        MccPokemonPortraits.pokemon("pvp-aspects", speciesId, formId, animate = false)?.aspects.orEmpty()

    private fun publicTypes(speciesId: String, aspects: Set<String>): List<ElementalType> {
        val species = ResourceLocation.tryParse(speciesId)?.let(PokemonSpecies::getByIdentifier) ?: return emptyList()
        val form = species.getForm(aspects)
        return listOfNotNull(form.primaryType, form.secondaryType)
    }

    private fun formName(speciesId: String, formId: String?): Component {
        if (formId.isNullOrBlank() || formId.equals("normal", ignoreCase = true)) return pvp("select.detail.base_form")
        return Component.literal(formId.replace('_', ' ').replace('-', ' '))
    }

    private fun remainingSeconds(): Long =
        ((state.selectionDeadlineEpochMillis - System.currentTimeMillis()).coerceAtLeast(0) + 999) / 1_000

    private fun UiRect.contains(px: Int, py: Int) = px >= x && px < right && py >= y && py < bottom

    private companion object {
        const val TEAM_SIZE = 6
        const val TOP_HEIGHT = 20
        const val BOTTOM_HEIGHT = 26
        const val HEADER_HEIGHT = 15
        const val CARD_GAP = 3
        const val STAT_ROW = 10
        const val MAX_FACES = 8
        val CURSOR = Any()
        val STATS: List<Stat> = listOf(Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED)

        /** A stable made-up id for a model drawn without a live Pokemon, so its idle animation keeps its state. */
        fun uuidFor(key: String): UUID = UUID.nameUUIDFromBytes(key.toByteArray())

        /** The stat at [level] by the games' formula from the form's base stat, the IV, the EV and the nature. */
        fun battleStat(pokemon: Pokemon, stat: Stat, level: Int): Int {
            val base = pokemon.form.baseStats[stat] ?: 0
            val iv = pokemon.ivs[stat] ?: 0
            val ev = pokemon.evs.getOrDefault(stat)
            val core = (2 * base + iv + ev / 4) * level / 100
            if (stat == Stats.HP) return if (base == 1) 1 else core + level + 10
            val nature = pokemon.effectiveNature
            val multiplier = when (stat) {
                nature.increasedStat -> 1.1
                nature.decreasedStat -> 0.9
                else -> 1.0
            }
            return ((core + 5) * multiplier).toInt()
        }

        fun pvp(key: String, vararg args: Any): Component = Component.translatable("screen.more_cobblemon_contents.pvp.$key", *args)
        fun speciesName(speciesId: String): Component = Component.translatable("cobblemon.species.${speciesId.substringAfter(':')}.name")
    }
}

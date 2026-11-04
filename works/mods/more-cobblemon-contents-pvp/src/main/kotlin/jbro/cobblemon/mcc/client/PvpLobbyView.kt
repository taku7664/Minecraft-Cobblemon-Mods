package jbro.cobblemon.mcc.client

import java.util.UUID
import jbro.cobblemon.mcc.client.hub.MccHubChipRows
import jbro.cobblemon.mcc.client.hub.MccHubContentHost
import jbro.cobblemon.mcc.client.hub.MccHubKit
import jbro.cobblemon.mcc.client.hub.MccHubScreen
import jbro.cobblemon.mcc.internal.pvp.PvpBattleFormat
import jbro.cobblemon.mcc.internal.pvp.PvpRankedRules
import jbro.cobblemon.mcc.internal.pvp.PvpRankedStanding
import jbro.cobblemon.mcc.internal.pvp.PvpRoomDefaults
import jbro.cobblemon.mcc.internal.pvp.PvpRoomSettings
import jbro.cobblemon.mcc.internal.pvp.PvpRoomVisibility
import jbro.cobblemon.mcc.internal.pvp.network.PvpRankedIntent
import jbro.cobblemon.mcc.internal.pvp.network.PvpRankedIntentPayload
import jbro.cobblemon.mcc.internal.pvp.network.PvpRankedLeader
import jbro.cobblemon.mcc.internal.pvp.network.PvpRankedStatePayload
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomIntent
import jbro.cobblemon.mcc.internal.pvp.network.PvpRoomIntentPayload
import jbro.cobblemon.uikit.CobblemonUiThemes
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiLayout
import jbro.cobblemon.uikit.UiModelFraming
import jbro.cobblemon.uikit.UiRect
import jbro.cobblemon.uikit.UiRenderSlotSpec
import jbro.cobblemon.uikit.UiThemeSnapshot
import jbro.cobblemon.uikit.client.CobblemonUiRenderContent
import jbro.cobblemon.uikit.client.CobblemonUiRenderSlot
import jbro.cobblemon.uikit.client.CobblemonUiTabBar
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

/** The ranked side of PvP as the server last told this client: standings, the queue and the leaders. */
internal object PvpRankedClientState {
    var standings: Map<PvpBattleFormat, PvpRankedStanding> = emptyMap()
        private set
    var queuedFormat: PvpBattleFormat? = null
        private set
    /** When this client's wait began, on this client's clock. */
    var queuedSinceMillis = 0L
        private set
    var queueSizes: Map<PvpBattleFormat, Int> = emptyMap()
        private set
    var leaders: Map<PvpBattleFormat, List<PvpRankedLeader>> = emptyMap()
        private set
    var feedbackKey: String? = null
        private set
    var feedbackArgs: List<String> = emptyList()
        private set
    var received = false
        private set
    var pending = false

    fun apply(payload: PvpRankedStatePayload) {
        received = true
        pending = false
        standings = payload.standings.associateBy(PvpRankedStanding::format)
        if (payload.queuedFormat != queuedFormat || payload.queuedFormat != null) {
            queuedSinceMillis = System.currentTimeMillis() - payload.queuedForMillis
        }
        queuedFormat = payload.queuedFormat
        queueSizes = payload.queueSizes
        leaders = payload.leaders
        // A plain update keeps the last refusal on screen; a new one replaces it.
        if (payload.messageKey != null) {
            feedbackKey = payload.messageKey
            feedbackArgs = payload.messageArgs
        }
    }

    fun clearFeedback() {
        feedbackKey = null
        feedbackArgs = emptyList()
    }

    fun queuedSeconds(): Long = ((System.currentTimeMillis() - queuedSinceMillis).coerceAtLeast(0)) / 1_000

    fun clear() {
        standings = emptyMap()
        queuedFormat = null
        queuedSinceMillis = 0
        queueSizes = emptyMap()
        leaders = emptyMap()
        received = false
        pending = false
        clearFeedback()
    }

    /** Sends [intent]; a server without ranked battles gets nothing, and nothing waits on its answer. */
    fun send(intent: PvpRankedIntent) {
        if (!ClientPlayNetworking.canSend(PvpRankedIntentPayload.TYPE)) return
        if (intent !is PvpRankedIntent.Refresh) {
            pending = true
            clearFeedback()
        }
        ClientPlayNetworking.send(PvpRankedIntentPayload(intent))
    }
}

/**
 * The PvP tab outside a room: tabs for ranked battles and custom rooms over a page with a status line, the player's
 * own card on the left and the chosen tab's card on the right, and a footer.
 */
internal data class PvpLobbyLayout(
    val tabs: UiRect,
    val page: UiRect,
    val profile: UiRect,
    val main: UiRect,
    val footer: UiRect,
) {
    companion object {
        const val PAGE_HEIGHT = 16

        fun calculate(bounds: UiRect): PvpLobbyLayout {
            val parts = UiLayout.column(gap = MccHubKit.GAP) {
                fixed(CobblemonUiTabBar.HEIGHT + PAGE_HEIGHT, UiLayout.column {
                    fixed(CobblemonUiTabBar.HEIGHT, "tabs")
                    weight("page", min = 1)
                })
                weight(UiLayout.row(gap = MccHubKit.GAP) {
                    weight("profile", 2, min = 1)
                    weight("main", 3, min = 1)
                }, min = 1)
                fixed(MccHubKit.FOOTER_HEIGHT, "footer")
            }.solve(bounds)
            return PvpLobbyLayout(parts["tabs"], parts["page"], parts["profile"], parts["main"], parts["footer"])
        }
    }
}

internal object PvpLobbyView {
    private const val RANKED = "ranked"
    private const val CUSTOM = "custom"
    private const val LINE = 10

    fun build(host: MccHubContentHost, bounds: UiRect) {
        val layout = PvpLobbyLayout.calculate(bounds)
        val section = PvpHubClient.section
        val items = listOf(
            CobblemonUiTabBar.Item(RANKED, lobby("tab.ranked")),
            CobblemonUiTabBar.Item(CUSTOM, lobby("tab.custom")),
        )
        CobblemonUiTabBar.create({ host.add(it) }, layout.tabs, layout.page.bottom, items,
            if (section == PvpHubClient.Section.RANKED) RANKED else CUSTOM) { id ->
            PvpHubClient.section = if (id == RANKED) PvpHubClient.Section.RANKED else PvpHubClient.Section.CUSTOM
            if (PvpHubClient.section == PvpHubClient.Section.RANKED) PvpRankedClientState.send(PvpRankedIntent.Refresh)
            else sendRoom(PvpRoomIntent.Refresh(UUID.randomUUID()))
            host.rebuild()
        }
        val status = if (section == PvpHubClient.Section.RANKED) {
            lobby("status.ranked", PvpRankedClientState.queueSizes[PvpBattleFormat.SINGLE] ?: 0,
                PvpRankedClientState.queueSizes[PvpBattleFormat.DOUBLE] ?: 0)
        } else {
            room("browser.summary", PvpRoomClientState.lastRooms.size)
        }
        host.add(PageLine(layout.page, status))
        profile(host, layout.profile)
        if (section == PvpHubClient.Section.RANKED) ranked(host, layout) else custom(host, layout)
    }

    /** The player's own card: their model, name and where they stand in the format picked on the ranked side. */
    private fun profile(host: MccHubContentHost, rect: UiRect) {
        val format = PvpHubClient.rankedFormat
        val body = MccHubKit.card(host, rect, lobby("profile.title", room("format.${format.recordId}")), MccHubKit.CardTone.FEATURE)
        val player = Minecraft.getInstance().player
        val columns = UiLayout.row(gap = 4) {
            fixed(MODEL_WIDTH.coerceAtMost(body.width / 4), "model")
            weight("lines", min = 1)
        }.solve(body)
        // The model stands beside the lines; on a tall hub it would otherwise sink to the card's foot.
        val model = columns["model"].let { UiRect(it.x, it.y, it.width, it.height.coerceAtMost(MODEL_HEIGHT)) }
        if (player != null) {
            host.add(CobblemonUiRenderSlot.create(model.x, model.y, model.width, model.height,
                UiRenderSlotSpec(player.name, framed = false),
                CobblemonUiRenderContent.PlayerProfile(player.gameProfile, UiModelFraming.FULL_BODY)))
        }
        val standing = PvpRankedClientState.standings[format]
        val lines = buildList {
            add(Line(player?.name ?: Component.empty(), Tone.TEXT))
            val games = standing?.let { it.wins + it.losses } ?: 0
            when {
                standing == null -> add(Line(lobby("profile.loading"), Tone.DIM))
                // Before the first game a place, a record and a peak say nothing; the rating alone does.
                games == 0 -> {
                    add(Line(lobby("profile.rating", standing.rating), Tone.ACCENT))
                    add(Line(lobby("profile.no_games"), Tone.DIM))
                }
                else -> {
                    add(Line(lobby("profile.rating", standing.rating), Tone.ACCENT))
                    add(Line(lobby("profile.rank", standing.rank, standing.ladderSize), Tone.TEXT))
                    add(Line(lobby("profile.record", standing.wins, standing.losses, Math.round(standing.wins * 100.0 / games).toInt()), Tone.TEXT))
                    add(Line(lobby("profile.peak", standing.peak), Tone.DIM))
                }
            }
        }
        host.add(Lines(columns["lines"], lines))
    }

    /** Ranked: the format to queue for, the wait or the rules, the leaders, and the queue button. */
    private fun ranked(host: MccHubContentHost, layout: PvpLobbyLayout) {
        val state = PvpRankedClientState
        val queued = state.queuedFormat
        if (queued != null) PvpHubClient.rankedFormat = queued
        val format = PvpHubClient.rankedFormat
        val body = MccHubKit.card(host, layout.main, lobby("ranked.title"))
        val idle = !state.pending
        val chips = MccHubChipRows.Row(lobby("ranked.format"),
            PvpBattleFormat.entries.map { MccHubChipRows.Option(it.recordId, pvp("format.${it.recordId}")) },
            setOf(format.recordId), enabled = queued == null && idle) { id ->
            PvpHubClient.rankedFormat = PvpBattleFormat.entries.first { it.recordId == id }
            host.rebuild()
        }
        val chipsBottom = MccHubChipRows.place(host, body, listOf(chips))
        val feedback = state.feedbackKey?.let { Component.translatable(it, *state.feedbackArgs.toTypedArray()) }
        val parts = UiLayout.column(gap = MccHubKit.GAP) {
            fixed(LINE * 2, "status")
            if (feedback != null) fixed(LINE * 2, "feedback")
            weight("leaders", min = 0)
        }.solve(MccHubKit.below(body, chipsBottom + MccHubKit.GAP))
        if (queued != null) {
            host.add(Lines(parts["status"], listOf(
                Line(lobby("ranked.searching", 0), Tone.ACCENT) { lobby("ranked.searching", clock(state.queuedSeconds())) },
            )))
        } else {
            MccHubKit.text(host, parts["status"], rules(format)) { MccHubKit.panelDim(it) }
        }
        feedback?.let { MccHubKit.text(host, parts["feedback"], it) { theme -> theme.colors.accentDanger } }
        val leadersRect = parts["leaders"]
        if (leadersRect.height >= LINE * 2) host.add(Leaders(leadersRect, state.leaders[format].orEmpty()))
        val action = if (queued != null) {
            MccHubKit.Action(lobby("ranked.leave"), UiButtonVariant.DANGER, idle, minWidth = 72) {
                PvpRankedClientState.send(PvpRankedIntent.Leave)
                host.rebuild()
            }
        } else {
            MccHubKit.Action(lobby("ranked.join"), UiButtonVariant.PRIMARY, idle && state.received, minWidth = 72) {
                PvpRankedClientState.send(PvpRankedIntent.Join(format))
                host.rebuild()
            }
        }
        MccHubKit.footer(host, layout.footer,
            listOf(MccHubKit.Action(room("browser.refresh")) { PvpRankedClientState.send(PvpRankedIntent.Refresh) }),
            listOf(action))
    }

    /** Custom rooms: the room list as it was, beside the player's card. */
    private fun custom(host: MccHubContentHost, layout: PvpLobbyLayout) {
        val rooms = PvpRoomClientState.lastRooms
        var body = MccHubKit.card(host, layout.main, room("browser.title"))
        PvpHubClient.listFeedbackKey?.let { key ->
            val feedback = Component.translatable(key, *PvpHubClient.listFeedbackArgs.toTypedArray())
            val (line, rest) = MccHubKit.lineAbove(body, LINE * 2)
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

    /** "Pick 3 · Mega · No duplicates" for [format]. */
    private fun rules(format: PvpBattleFormat): Component {
        val mechanics = PvpRankedRules.ENABLED_MECHANICS.map { room("mechanic.${it.id}") }
        val gimmicks = if (mechanics.isEmpty()) lobby("ranked.rules.no_gimmicks")
            else mechanics.reduce { acc, next -> Component.empty().append(acc).append(", ").append(next) }
        return lobby("ranked.rules", format.selectionSize, gimmicks)
    }

    private fun clock(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

    private fun create(visibility: PvpRoomVisibility) = sendRoom(PvpRoomIntent.Create(UUID.randomUUID(),
        PvpRoomSettings(visibility, PvpBattleFormat.SINGLE, PvpRoomDefaults.ENABLED_MECHANICS)))

    private fun sendRoom(intent: PvpRoomIntent) = PvpPlayClientNetworking.send(PvpRoomIntentPayload(intent))

    private const val MODEL_WIDTH = 36
    /** Six lines of the player's card. */
    private const val MODEL_HEIGHT = LINE * 6

    private enum class Tone { TEXT, DIM, ACCENT }

    /** One line of a card; [live] is read every frame, for a running clock. */
    private class Line(val text: Component, val tone: Tone, val live: (() -> Component)? = null)

    private fun color(theme: UiThemeSnapshot, tone: Tone): Int = when (tone) {
        Tone.TEXT -> MccHubKit.panelText(theme)
        Tone.DIM -> MccHubKit.panelDim(theme)
        Tone.ACCENT -> theme.colors.accentPrimary
    }

    /** Lines from the top of [rect], each cut to its width; those that do not fit are left out. */
    private class Lines(private val rect: UiRect, private val lines: List<Line>) :
        AbstractWidget(rect.x, rect.y, rect.width, rect.height, Component.empty()) {
        init { active = false }
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            val font = Minecraft.getInstance().font
            lines.forEachIndexed { index, line ->
                val y = rect.y + index * LINE
                if (y + font.lineHeight > rect.bottom) return
                graphics.drawString(font, MccHubKit.fitted(line.live?.invoke() ?: line.text, rect.width), rect.x, y, color(theme, line.tone), false)
            }
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    /** The format's leaders: a heading, then rank, name and rating per line, as many as fit. */
    private class Leaders(private val rect: UiRect, private val leaders: List<PvpRankedLeader>) :
        AbstractWidget(rect.x, rect.y, rect.width, rect.height, Component.empty()) {
        init { active = false }
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            val font = Minecraft.getInstance().font
            graphics.fill(rect.x, rect.y, rect.right, rect.y + 1, theme.colors.border)
            graphics.drawString(font, MccHubKit.fitted(lobby("ranked.leaders"), rect.width), rect.x, rect.y + 3, MccHubKit.panelDim(theme), false)
            if (leaders.isEmpty()) {
                graphics.drawString(font, MccHubKit.fitted(lobby("ranked.leaders.empty"), rect.width), rect.x, rect.y + 3 + LINE,
                    MccHubKit.panelDim(theme), false)
                return
            }
            val rankWidth = font.width("10.") + 3
            leaders.forEachIndexed { index, leader ->
                val y = rect.y + 3 + (index + 1) * LINE
                if (y + font.lineHeight > rect.bottom) return
                val rating = leader.rating.toString()
                val ratingX = rect.right - font.width(rating)
                graphics.drawString(font, "${leader.rank}.", rect.x, y, MccHubKit.panelDim(theme), false)
                graphics.drawString(font, font.plainSubstrByWidth(leader.name, (ratingX - rect.x - rankWidth - 4).coerceAtLeast(1)),
                    rect.x + rankWidth, y, MccHubKit.panelText(theme), false)
                graphics.drawString(font, rating, ratingX, y, theme.colors.accentPrimary, false)
            }
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    /** The status line on the page under the tabs, in the page's text colour. */
    private class PageLine(private val rect: UiRect, private val text: Component) :
        AbstractWidget(rect.x, rect.y, rect.width, rect.height, text) {
        init { active = false }
        override fun renderWidget(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
            val theme = CobblemonUiThemes.registry.snapshot()
            val font = Minecraft.getInstance().font
            val line = MccHubKit.fitted(text, (rect.width - 12).coerceAtLeast(1))
            graphics.drawString(font, line, rect.x + 6, rect.y + (rect.height - font.lineHeight) / 2 + 1,
                CobblemonUiTabBar.pageText(theme), false)
        }
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit
    }

    /** Opens the hub on the ranked tab, or shows it there when the hub is up on PvP already. */
    fun showRanked() {
        PvpHubClient.section = PvpHubClient.Section.RANKED
        if (MccHubScreen.showing(PvpHubClient.CONTENT)) MccHubScreen.refresh(PvpHubClient.CONTENT)
        else MccHubScreen.open(PvpHubClient.CONTENT)
    }
}

private fun lobby(key: String, vararg args: Any): Component = Component.translatable("screen.more_cobblemon_contents.pvp.lobby.$key", *args)
private fun room(key: String, vararg args: Any): Component = Component.translatable("screen.more_cobblemon_contents.pvp.room.$key", *args)
private fun pvp(key: String, vararg args: Any): Component = Component.translatable("screen.more_cobblemon_contents.pvp.$key", *args)

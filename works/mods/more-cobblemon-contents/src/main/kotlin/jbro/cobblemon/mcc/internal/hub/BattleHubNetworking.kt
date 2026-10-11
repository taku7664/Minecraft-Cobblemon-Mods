package jbro.cobblemon.mcc.internal.hub

import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.api.access.BattleContentAccess
import jbro.cobblemon.mcc.api.access.ContentAccessAction
import jbro.cobblemon.mcc.api.access.ContentAccessDecision
import jbro.cobblemon.mcc.api.terminal.HoloTerminal
import jbro.cobblemon.mcc.api.hub.MccDashboardCard
import jbro.cobblemon.mcc.api.hub.MccDashboardContext
import jbro.cobblemon.mcc.api.hub.MccDashboardSections
import jbro.cobblemon.mcc.internal.bp.BattlePointService
import jbro.cobblemon.mcc.internal.presentation.attemptServerUiOperation
import jbro.cobblemon.mcc.internal.record.BattleRecordService
import jbro.cobblemon.mcc.internal.terminal.TerminalInteractionResult
import java.util.UUID
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

object BattleHubNetworking {
    /** The tabs a player's open hub may reach, and the terminal it was opened from; null for `/mcc`. */
    private class Session(val tabs: List<String>, val terminal: TerminalInteractionResult.Verified?)

    private val sessions = HashMap<UUID, Session>()

    fun registerServer() {
        PayloadTypeRegistry.playS2C().register(BattleHubAccessPayload.TYPE, BattleHubAccessPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(BattleHubStatePayload.TYPE, BattleHubStatePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(BattleHubHeaderStatePayload.TYPE, BattleHubHeaderStatePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(BattleHubDashboardPayload.TYPE, BattleHubDashboardPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(BattleHubOpenContentPayload.TYPE, BattleHubOpenContentPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(BattleHubOpenContentPayload.TYPE) { payload, context ->
            val player = context.player()
            val opened = attemptServerUiOperation(
                reportFailure = { failure -> reportFailure(player, "content ${payload.contentId}", failure) },
            ) {
                // A tab the hub was not opened with stays closed, whatever the client asks for.
                val session = sessionReaching(player, payload.contentId)
                session != null && BattleHubEntries.get(payload.contentId)?.open?.invoke(player, session.terminal) == true
            }
            if (!opened) {
                attemptServerUiOperation(
                    reportFailure = { failure -> reportFailure(player, "unavailable response", failure) },
                ) {
                    player.sendSystemMessage(
                        Component.translatable(
                            "screen.${MoreCobblemonContents.MOD_ID}.hub.unavailable.${payload.contentId.substringAfter(':')}",
                        ),
                    )
                    true
                }
            }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            sessions.remove(handler.player.uuid)
        }
    }

    /** Opens the hub for `/mcc`, on the tabs the config gives the command. */
    fun openCommand(player: ServerPlayer): Boolean =
        open(player, BattleHubTabConfigFile.current.command, BattleHubIds.DASHBOARD, null)

    /** The `/mcc` tabs opened on the shop, for a shopkeeper NPC; the first tab when the config leaves the shop out. */
    fun openShopCommand(player: ServerPlayer): Boolean =
        open(player, BattleHubTabConfigFile.current.command, BattleHubIds.SHOP, null)

    /** Opens the hub from [terminal]; the verified [use] stays attached to the entries opened during this hub session. */
    fun openTerminal(player: ServerPlayer, terminal: HoloTerminal, use: TerminalInteractionResult.Verified): Boolean {
        val tabs = BattleHubTabConfigFile.current.terminalTabs(terminal.id.toString(), terminal.defaultTabs)
        return open(player, tabs, BattleHubIds.DASHBOARD, use)
    }

    /** NPC/server commands share terminal tabs, but the session has no block identity or distance restriction. */
    fun openTerminalCommand(player: ServerPlayer, terminal: HoloTerminal): Boolean =
        open(player, BattleHubTabConfigFile.current.terminalTabs(terminal.id.toString(), terminal.defaultTabs),
            BattleHubIds.DASHBOARD, null)

    /** Shows [configured] on [home], or on the first tab when the list leaves [home] out. */
    private fun open(player: ServerPlayer, configured: List<String>, home: String, terminal: TerminalInteractionResult.Verified?): Boolean {
        val tabs = configured.take(BattleHubStatePayload.MAX_TABS)
        val opened = attemptServerUiOperation(
            reportFailure = { failure -> reportFailure(player, "open", failure) },
        ) {
            if (!ServerPlayNetworking.canSend(player, BattleHubStatePayload.TYPE)) return@attemptServerUiOperation false
            sessions[player.uuid] = Session(tabs, terminal)
            sendHeader(player)
            sendDashboard(player)
            ServerPlayNetworking.send(player, BattleHubStatePayload(tabs, home.takeIf { it in tabs } ?: tabs.first()))
            true
        }
        if (!opened) sessions.remove(player.uuid)
        return opened
    }

    fun sendHeader(player: ServerPlayer): Boolean {
        return attemptServerUiOperation(
            reportFailure = { failure -> reportFailure(player, "header", failure) },
        ) {
            if (!ServerPlayNetworking.canSend(player, BattleHubHeaderStatePayload.TYPE)) return@attemptServerUiOperation false
            ServerPlayNetworking.send(player, BattleHubHeaderStatePayload(balance(player)))
            if (ServerPlayNetworking.canSend(player, BattleHubAccessPayload.TYPE)) {
                val denied = BattleHubEntries.all().mapNotNull { entry ->
                    val accessContentId = entry.accessContentId ?: return@mapNotNull null
                    val decision = BattleContentAccess.check(player, accessContentId, ContentAccessAction.OPEN)
                    (decision as? ContentAccessDecision.Denied)?.let { entry.contentId to it }
                }.toMap()
                ServerPlayNetworking.send(player, BattleHubAccessPayload(denied))
            }
            true
        }
    }

    private fun sendDashboard(player: ServerPlayer) {
        if (!ServerPlayNetworking.canSend(player, BattleHubDashboardPayload.TYPE)) return
        val records = BattleRecordService.forPlayer(player.server, player.uuid)
            .sortedWith(compareBy({ it.key.category.contentId }, { it.key.category.formatId }))
            .take(BattleHubDashboardPayload.MAX_RECORDS)
            .map(BattleHubRecordView::from)
        val context = MccDashboardContext.of(player.server, player, records)
        val cards = MccDashboardSections.build(context, records).map { card -> withAccessNote(player, card) }
            .take(BattleHubDashboardPayload.MAX_CARDS)
        ServerPlayNetworking.send(player, BattleHubDashboardPayload(records.sumOf { it.battles }, records.sumOf { it.wins }, cards))
    }

    /** A card of a content the player cannot open yet says why, unless its section already wrote a note. */
    private fun withAccessNote(player: ServerPlayer, card: MccDashboardCard): MccDashboardCard {
        if (card.note != null) return card
        val denied = BattleContentAccess.check(player, card.contentId, ContentAccessAction.OPEN) as? ContentAccessDecision.Denied ?: return card
        return card.copy(note = Component.translatable(denied.reasonKey, *denied.arguments.toTypedArray()))
    }

    /** True while [player]'s open hub may still reach [contentId]; contents with packets of their own check this. */
    fun reaches(player: ServerPlayer, contentId: String): Boolean = sessionReaching(player, contentId) != null

    /**
     * The hub session that may open [contentId] now. A session opened at a terminal lasts only while the player is
     * alive and still at it, so a verified use cannot be carried elsewhere and replayed by a modified client.
     */
    private fun sessionReaching(player: ServerPlayer, contentId: String): Session? {
        val session = sessions[player.uuid]?.takeIf { contentId in it.tabs } ?: return null
        val terminal = session.terminal ?: return session
        if (player.isAlive && !player.isRemoved && terminalStillInReach(
                player.serverLevel().dimension().location().toString(), player.x, player.y, player.z, terminal)
        ) return session
        sessions.remove(player.uuid, session)
        return null
    }

    fun clear() = sessions.clear()

    /** Development hub captures open the hub on the client alone; this lets the server open [tabs] for it. */
    internal fun openForCapture(player: ServerPlayer, tabs: List<String>) {
        sessions[player.uuid] = Session(tabs, null)
    }

    private fun balance(player: ServerPlayer): Long = BattlePointService.balance(player.server, player.uuid)

    private fun reportFailure(player: ServerPlayer, operation: String, failure: Throwable) {
        MoreCobblemonContents.LOGGER.error("Battle Hub $operation failed for ${player.uuid}", failure)
    }
}

/** How far a player may step from the terminal they opened the hub at and still use it; the use itself allows 8. */
internal const val HUB_TERMINAL_REACH = 16.0

/** True when a player in [dimensionId] at ([x], [y], [z]) is still within [HUB_TERMINAL_REACH] of [terminal]. */
internal fun terminalStillInReach(
    dimensionId: String,
    x: Double,
    y: Double,
    z: Double,
    terminal: TerminalInteractionResult.Verified,
): Boolean {
    if (dimensionId != terminal.dimensionId || !x.isFinite() || !y.isFinite() || !z.isFinite()) return false
    val dx = x - (terminal.x + 0.5)
    val dy = y - (terminal.y + 0.5)
    val dz = z - (terminal.z + 0.5)
    return dx * dx + dy * dy + dz * dz <= HUB_TERMINAL_REACH * HUB_TERMINAL_REACH
}

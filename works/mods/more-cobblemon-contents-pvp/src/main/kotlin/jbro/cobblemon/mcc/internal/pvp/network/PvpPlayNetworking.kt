package jbro.cobblemon.mcc.internal.pvp.network

import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import jbro.cobblemon.mcc.api.access.ContentAccessDecision
import jbro.cobblemon.mcc.api.access.MccContentSwitches
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.internal.hub.BattleHubEntry
import jbro.cobblemon.mcc.internal.hub.BattleHubEntries
import jbro.cobblemon.mcc.internal.battle.ManagedTurnInterceptors
import java.util.UUID
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.command.PvpCommandBackend
import jbro.cobblemon.mcc.internal.command.PvpCommandOutcome
import jbro.cobblemon.mcc.internal.command.PvpCommandStatus
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173PvpBattleRuntime
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173ManagedBattleTermination
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173PvpLoungeGateway
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173PvpRegisteredTeamSnapshotStore
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173PvpTeamFactory
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173PvpTurnHooks
import jbro.cobblemon.mcc.internal.compat.cobblemon173.reportManagedCleanupFailureSafely
import jbro.cobblemon.mcc.internal.compat.cobblemon173.runManagedCleanupActions
import jbro.cobblemon.mcc.internal.compat.cobblemon173.runManagedCleanupActionsSafely
import jbro.cobblemon.mcc.internal.compat.cobblemon173.runManagedCleanupForEachSafely
import jbro.cobblemon.mcc.internal.pvp.PvpBattleFormat
import jbro.cobblemon.mcc.internal.pvp.PvpArenaPool
import jbro.cobblemon.mcc.internal.pvp.PvpBattleCompletionSink
import jbro.cobblemon.mcc.internal.pvp.PvpBattleLauncher
import jbro.cobblemon.mcc.internal.pvp.PvpBattleRecordService
import jbro.cobblemon.mcc.internal.pvp.PvpMatchHistory
import jbro.cobblemon.mcc.internal.pvp.PvpBattleLaunchResult
import jbro.cobblemon.mcc.internal.pvp.PvpChallengeMutationError
import jbro.cobblemon.mcc.internal.pvp.PvpChallengeMutationResult
import jbro.cobblemon.mcc.internal.pvp.PvpChallengePhase
import jbro.cobblemon.mcc.internal.pvp.PvpChallengeRequest
import jbro.cobblemon.mcc.internal.pvp.applyPvpInvitationWithNotification
import jbro.cobblemon.mcc.internal.pvp.protectProvisionalPvpStateNotification
import jbro.cobblemon.mcc.internal.pvp.PendingPvpCompletion
import jbro.cobblemon.mcc.internal.pvp.PvpCompletionRetryQueue
import jbro.cobblemon.mcc.internal.pvp.attemptPvpCompletionSettlement
import jbro.cobblemon.mcc.internal.pvp.transitionPvpBattleLifecycle
import jbro.cobblemon.mcc.internal.pvp.PVP_COMPLETION_RETRY_MILLIS
import jbro.cobblemon.mcc.internal.pvp.PvpMatchPhase
import jbro.cobblemon.mcc.internal.pvp.PvpLoungeCoordinator
import jbro.cobblemon.mcc.internal.pvp.PvpLoungeRescuePolicy
import jbro.cobblemon.mcc.internal.pvp.PvpRoomError
import jbro.cobblemon.mcc.internal.pvp.PvpRoomBattlePlacement
import jbro.cobblemon.mcc.internal.pvp.PvpRoomMutation
import jbro.cobblemon.mcc.internal.pvp.PvpRoomService
import jbro.cobblemon.mcc.internal.pvp.PvpRankedEntry
import jbro.cobblemon.mcc.internal.pvp.PvpRankedHistory
import jbro.cobblemon.mcc.internal.pvp.PvpRankedPairing
import jbro.cobblemon.mcc.internal.pvp.PvpRankedQueue
import jbro.cobblemon.mcc.internal.pvp.PvpRankedResult
import jbro.cobblemon.mcc.internal.pvp.PvpRankedRules
import jbro.cobblemon.mcc.internal.pvp.PvpRoomSide
import jbro.cobblemon.mcc.internal.command.MccAdminArguments
import kotlin.math.roundToInt
import jbro.cobblemon.mcc.internal.pvp.PvpRoomView
import jbro.cobblemon.mcc.internal.pvp.leaveRequestError
import jbro.cobblemon.mcc.internal.pvp.PvpSelectionMutation
import jbro.cobblemon.mcc.internal.pvp.PvpSessionService
import jbro.cobblemon.mcc.internal.pvp.PvpTeamRegistrationResult
import jbro.cobblemon.mcc.internal.pvp.PvpTeamIssue
import jbro.cobblemon.mcc.internal.pvp.PvpTurnCapture
import jbro.cobblemon.mcc.internal.pvp.ui.PvpSelectionIntent
import jbro.cobblemon.mcc.internal.pvp.ui.PvpSelectionOpponentSlot
import jbro.cobblemon.mcc.internal.pvp.ui.PvpSelectionPartySlot
import jbro.cobblemon.mcc.internal.pvp.ui.PvpSelectionSpectator
import jbro.cobblemon.mcc.internal.pvp.ui.PvpSelectionViewState
import jbro.cobblemon.mcc.internal.presentation.attemptServerUiOperation
import jbro.cobblemon.mcc.internal.hub.BattleHubNetworking
import jbro.cobblemon.mcc.internal.record.BattleRecordService
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import jbro.cobblemon.mcc.internal.command.MccAdminSource
import jbro.cobblemon.mcc.internal.command.MccAdminSources
import jbro.cobblemon.mcc.internal.command.MccCommandContributors
import jbro.cobblemon.mcc.internal.command.MccPendingResult

internal object PvpPlayNetworking : PvpCommandBackend {
    private val onlinePlayers = HashMap<UUID, ServerPlayer>()
    /** Each lobby's invite list as last sent, so a player's busy state changing anywhere reaches every host. */
    private val lobbyInviteSignatures = HashMap<UUID, Int>()
    private val retryableMatches = HashSet<UUID>()
    private val pendingCompletions = PvpCompletionRetryQueue()
    private val rooms = PvpRoomService()
    private val rankedQueue = PvpRankedQueue()
    /** Rooms the ranked queue made for its matches, with their format. Nobody sees them as rooms. */
    private val rankedRooms = HashMap<UUID, RankedMatch>()
    /**
     * Ranked matches by id until their result settles. Kept apart from [rankedRooms], which forgets a match with its
     * room, because a settlement retried after a failed write runs once the room is already gone.
     */
    private val rankedMatches = HashMap<UUID, RankedMatch>()
    /** Ranked matches whose battle began; one that ends before this is settled at once or forfeited. */
    private val rankedLaunched = HashSet<UUID>()
    private var currentServer: MinecraftServer? = null
    private val loungeGateway by lazy { Cobblemon173PvpLoungeGateway({ currentServer }, ::livePlayer) }
    private val arenas = PvpArenaPool()
    private val lounge by lazy { PvpLoungeCoordinator(arenas, loungeGateway) }
    private val snapshots = Cobblemon173PvpRegisteredTeamSnapshotStore(::livePlayer)
    private val runtime: Cobblemon173PvpBattleRuntime by lazy {
        Cobblemon173PvpBattleRuntime(
            playerResolver = ::livePlayer,
            completion = { server, matchId, winnerId, loserId, battleId, displayNames ->
                val pending = PendingPvpCompletion(matchId, battleId, winnerId, loserId, displayNames)
                pendingCompletions.submit(pending) { completion -> settleCompletion(server, completion) }
            },
            cancellation = { server, matchId, battleId ->
                val pending = PendingPvpCompletion.cancelled(matchId, battleId)
                pendingCompletions.submit(pending) { completion -> settleCompletion(server, completion) }
            },
        )
    }
    private val launcher: PvpBattleLauncher<BattlePokemon> by lazy {
        PvpBattleLauncher(
            materialize = snapshots,
            runtime = runtime,
            placement = PvpRoomBattlePlacement(rooms, lounge),
            abortBattle = Cobblemon173ManagedBattleTermination::end,
            diagnostics = { reason -> MoreCobblemonContents.LOGGER.error("PvP launch failed: {}", reason) },
        )
    }
    private val sessions: PvpSessionService<BattlePokemon> by lazy {
        PvpSessionService(
            snapshots = snapshots,
            launcher = launcher,
        )
    }
    private val turnHooks by lazy { Cobblemon173PvpTurnHooks(sessions::timerForBattle) }

    @JvmStatic
    fun observeBattleTurn(battle: PokemonBattle) {
        runManagedCleanupActionsSafely(
            reportFailure = { failure ->
                reportPvpFailureSafely(
                    "PvP turn observation failed for battle ${battle.battleId}",
                    failure,
                )
            },
            { turnHooks.observe(battle) },
        )
    }

    @JvmStatic
    fun captureBattleTurn(actor: BattleActor): PvpTurnCapture? = turnHooks.capture(actor)

    @JvmStatic
    fun acceptBattleTurn(capture: PvpTurnCapture) = turnHooks.accept(capture)

    @JvmStatic
    fun rejectBattleTurn(capture: PvpTurnCapture) = turnHooks.reject(capture)

    @JvmStatic
    fun resolveTimedOutBattleTurn(actor: BattleActor, capture: PvpTurnCapture) =
        turnHooks.resolveTimedOut(actor, capture)

    @JvmStatic
    fun forgetBattleTurn(battleId: UUID) = turnHooks.forget(battleId)

    fun registerServer() {
        sessions
        ManagedTurnInterceptors.register(PvpManagedTurnInterceptor)
        MccCommandContributors.register { jbro.cobblemon.mcc.internal.pvp.PvpAdminCommands.build() }
        MccAdminSources.register(adminSource)
        BattleHubEntries.register(
            BattleHubEntry(ManagedBattleContentIds.PVP, accessContentId = null) { player, _ ->
                open(player).status == PvpCommandStatus.APPLIED
            },
        )
        // A room or a match already joined goes on while PvP is switched off; leaving it is always allowed.
        MccContentSwitches.registerRunProbe(ManagedBattleContentIds.PVP) { _, playerId ->
            rooms.roomFor(playerId) != null || sessions.challengeFor(playerId) != null
        }
        PayloadTypeRegistry.playS2C().register(PvpSelectionStatePayload.TYPE, PvpSelectionStatePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(PvpSelectionRejectedPayload.TYPE, PvpSelectionRejectedPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(PvpSelectionClosedPayload.TYPE, PvpSelectionClosedPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(PvpRoomListStatePayload.TYPE, PvpRoomListStatePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(PvpRoomStatePayload.TYPE, PvpRoomStatePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(PvpRoomRejectedPayload.TYPE, PvpRoomRejectedPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(PvpRoomInvitePayload.TYPE, PvpRoomInvitePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(PvpLoungeSpectatorStatePayload.TYPE, PvpLoungeSpectatorStatePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(PvpLoungeExitResultPayload.TYPE, PvpLoungeExitResultPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(PvpSelectionIntentPayload.TYPE, PvpSelectionIntentPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(PvpRoomIntentPayload.TYPE, PvpRoomIntentPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(PvpLoungeExitPayload.TYPE, PvpLoungeExitPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(PvpRankedStatePayload.TYPE, PvpRankedStatePayload.CODEC)
        PayloadTypeRegistry.playC2S().register(PvpRankedIntentPayload.TYPE, PvpRankedIntentPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(PvpRankedIntentPayload.TYPE) { payload, context ->
            val player = context.player()
            onlinePlayers[player.uuid] = player
            try {
                handleRankedIntent(player, payload.intent)
            } catch (exception: RuntimeException) {
                rejectFailedRankedRequest(player, exception)
            } catch (error: LinkageError) {
                rejectFailedRankedRequest(player, error)
            }
        }
        ServerPlayNetworking.registerGlobalReceiver(PvpSelectionIntentPayload.TYPE) { payload, context ->
            val player = context.player()
            onlinePlayers[player.uuid] = player
            try {
                handleIntent(player, payload.intent)
            } catch (exception: RuntimeException) {
                rejectFailedSelectionRequest(player, payload.intent, exception)
            } catch (error: LinkageError) {
                rejectFailedSelectionRequest(player, payload.intent, error)
            }
        }
        ServerPlayNetworking.registerGlobalReceiver(PvpRoomIntentPayload.TYPE) { payload, context ->
            val player = context.player()
            onlinePlayers[player.uuid] = player
            try {
                handleRoomIntent(player, payload.intent)
            } catch (exception: RuntimeException) {
                rejectFailedRoomRequest(player, payload.intent.requestId, exception)
            } catch (error: LinkageError) {
                rejectFailedRoomRequest(player, payload.intent.requestId, error)
            }
        }
        ServerPlayNetworking.registerGlobalReceiver(PvpLoungeExitPayload.TYPE) { _, context ->
            val player = context.player()
            onlinePlayers[player.uuid] = player
            val result = try {
                when (exitSpectator(player)) {
                    PvpSpectatorExitResult.ACCEPTED -> PvpLoungeExitResultPayload(accepted = true, messageKey = null)
                    PvpSpectatorExitResult.INVALID_STATE -> PvpLoungeExitResultPayload(
                        accepted = false,
                        messageKey = "screen.${MoreCobblemonContents.MOD_ID}.pvp.error.invalid_state",
                    )
                    PvpSpectatorExitResult.INTERNAL_FAILURE -> PvpLoungeExitResultPayload(
                        accepted = false,
                        messageKey = "screen.${MoreCobblemonContents.MOD_ID}.pvp.error.internal_failure",
                    )
                }
            } catch (exception: RuntimeException) {
                reportPvpFailureSafely("PvP spectator exit failed for ${player.uuid}", exception)
                PvpLoungeExitResultPayload(
                    accepted = false,
                    messageKey = "screen.${MoreCobblemonContents.MOD_ID}.pvp.error.internal_failure",
                )
            } catch (error: LinkageError) {
                reportPvpFailureSafely("PvP spectator exit failed for ${player.uuid}", error)
                PvpLoungeExitResultPayload(
                    accepted = false,
                    messageKey = "screen.${MoreCobblemonContents.MOD_ID}.pvp.error.internal_failure",
                )
            }
            runManagedCleanupActionsSafely(
                reportFailure = { failure ->
                    MoreCobblemonContents.LOGGER.error("PvP spectator exit response failed for ${player.uuid}", failure)
                },
                {
                    if (ServerPlayNetworking.canSend(player, PvpLoungeExitResultPayload.TYPE)) {
                        ServerPlayNetworking.send(player, result)
                    }
                },
            )
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val player = handler.player
            runManagedCleanupActionsSafely(
                reportFailure = { failure ->
                    MoreCobblemonContents.LOGGER.error("PvP join recovery failed for ${player.uuid}", failure)
                },
                { onlinePlayers[player.uuid] = player },
                { rescueStrandedLoungePlayer(player) },
                { pushLobbyRooms() },
            )
        }
        // Respawning replaces the ServerPlayer; keep the live one, or teleports and game mode changes hit the old body.
        ServerPlayerEvents.AFTER_RESPAWN.register { _, newPlayer, _ ->
            if (newPlayer.uuid in onlinePlayers) onlinePlayers[newPlayer.uuid] = newPlayer
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            val playerId = handler.player.uuid
            var room: PvpRoomView? = null
            var remaining: PvpRoomView? = null
            runManagedCleanupActionsSafely(
                reportFailure = { failure ->
                    MoreCobblemonContents.LOGGER.error("PvP disconnect cleanup failed for $playerId", failure)
                },
                { onlinePlayers.remove(playerId) },
                { if (rankedQueue.leave(playerId) != null) pushRankedQueueSizes() },
                { room = rooms.roomFor(playerId) },
                {
                    val challenge = sessions.challengeFor(playerId)
                    if (challenge != null &&
                        challenge.phase in setOf(PvpChallengePhase.PENDING, PvpChallengePhase.TEAM_REGISTRATION)
                    ) {
                        val matchId = challenge.request.challengeId
                        runManagedCleanupActions(
                            {
                                if (sessions.cancel(matchId, playerId) is PvpChallengeMutationResult.Applied) {
                                    forfeitRankedSelection(handler.player.server, matchId, playerId)
                                }
                            },
                            { retryableMatches.remove(matchId) },
                            {
                                notifyClosed(
                                    challenge.request,
                                    "screen.${MoreCobblemonContents.MOD_ID}.pvp.closed.disconnected",
                                )
                            },
                            { finishRoom(matchId) },
                        )
                    }
                    if (challenge != null && challenge.phase == PvpChallengePhase.ACTIVE) {
                        val matchId = challenge.request.challengeId
                        if (matchId in pendingCompletions) {
                            // The Cobblemon battle already ended; preserve its unsettled result even if a
                            // participant disconnects while record storage is temporarily unavailable.
                            processPendingCompletions(handler.player.server, force = true)
                        } else {
                            runManagedCleanupActions(
                                {
                                    sessions.disconnectActiveBattle(
                                        playerId,
                                        Cobblemon173ManagedBattleTermination::end,
                                    )
                                },
                                { retryableMatches.remove(matchId) },
                                { finishRoom(matchId) },
                            )
                        }
                    }
                },
                {
                    val disconnectedRoom = room
                    if (disconnectedRoom?.phase == jbro.cobblemon.mcc.internal.pvp.PvpRoomPhase.ACTIVE &&
                        playerId in disconnectedRoom.spectatorIds
                    ) {
                        lounge.disconnectSpectator(disconnectedRoom.roomId, playerId)
                    }
                },
                { remaining = rooms.disconnect(playerId) },
                // Every lobby's invite list drops the player who left, not only their own room's.
                { pushLobbyRooms(except = remaining?.roomId) },
                {
                    remaining?.let { updatedRoom ->
                        pushRoomToMembers(updatedRoom, null)
                        if (updatedRoom.phase == jbro.cobblemon.mcc.internal.pvp.PvpRoomPhase.TEAM_PREVIEW) {
                            pushSpectatorPreview(updatedRoom)
                        }
                    }
                },
            )
        }
        ServerLifecycleEvents.SERVER_STARTING.register { server -> currentServer = server }
        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            runManagedCleanupActionsSafely(
                reportFailure = { failure ->
                    MoreCobblemonContents.LOGGER.error("PvP server shutdown cleanup failed", failure)
                },
                { processPendingCompletions(server, force = true) },
                {
                    sessions.activeBattles().values.toSet().forEach { battleId ->
                        runManagedCleanupActionsSafely(
                            reportFailure = { failure ->
                                MoreCobblemonContents.LOGGER.error(
                                    "PvP battle $battleId could not be terminated during server shutdown",
                                    failure,
                                )
                            },
                            { Cobblemon173ManagedBattleTermination.end(battleId) },
                        )
                    }
                },
                // Ending a battle invokes its completion callback and can enqueue a fresh settlement.
                // Flush those results before session state and retry ownership are discarded.
                { processPendingCompletions(server, force = true) },
                lounge::shutdown,
                sessions::clear,
                rooms::clear,
                rankedQueue::clear,
                rankedRooms::clear,
                rankedMatches::clear,
                rankedLaunched::clear,
                retryableMatches::clear,
                {
                    if (pendingCompletions.size() > 0) {
                        MoreCobblemonContents.LOGGER.error(
                            "Discarding {} PvP completion retries because the server is stopping and record storage is still unavailable",
                            pendingCompletions.size(),
                        )
                    }
                },
                pendingCompletions::clear,
                turnHooks::clear,
                onlinePlayers::clear,
                { if (currentServer === server) currentServer = null },
            )
        }
        ServerTickEvents.END_SERVER_TICK.register { server ->
            runManagedCleanupActionsSafely(
                reportFailure = { failure ->
                    MoreCobblemonContents.LOGGER.error("PvP server tick cleanup failed", failure)
                },
                { currentServer = server },
                ::processEntryTimeouts,
                turnHooks::processTimeouts,
                { processPendingCompletions(server) },
                { lounge.restoreAvailable(onlinePlayers::containsKey) },
                loungeGateway::enforceSpectatorAnchors,
                { if (server.tickCount % LOBBY_INVITE_REFRESH_TICKS == 0) refreshChangedLobbyInvites() },
                { if (server.tickCount % RANKED_PAIRING_TICKS == 0) processRankedQueue(server) },
            )
        }
    }

    /**
     * Resends each lobby whose invite list changed since it was last sent here: a candidate created, joined or left a
     * room, or a challenge began or ended. A room seen for the first time is sent once too, since something may have
     * changed between its own push and this check.
     */
    private fun refreshChangedLobbyInvites() {
        val lobbies = rooms.all().filter { it.phase == jbro.cobblemon.mcc.internal.pvp.PvpRoomPhase.LOBBY }
        lobbyInviteSignatures.keys.retainAll(lobbies.mapTo(HashSet()) { it.roomId })
        lobbies.forEach { room ->
            val view = clientView(room)
            val signature = (view.inviteCandidates.map(PvpRoomMemberView::playerId) to view.invitedIds).hashCode()
            val previous = lobbyInviteSignatures.put(room.roomId, signature)
            if (previous != signature) pushRoomToMembers(room, null)
        }
    }

    /** Every room, private ones included, with its match's battle when one is running. */
    fun adminRooms(): List<Pair<jbro.cobblemon.mcc.internal.pvp.PvpRoomView, UUID?>> =
        rooms.all().map { it to sessions.battleIdFor(it.roomId) }

    fun adminRoomFor(playerId: UUID): jbro.cobblemon.mcc.internal.pvp.PvpRoomView? = rooms.roomFor(playerId)

    /**
     * Closes [roomId] for an operator: its battle ends without a result, a match still being set up is cancelled,
     * everyone leaves the lounge and every member gets the room list back. Returns false when there is no room.
     */
    fun adminCloseRoom(server: MinecraftServer, roomId: UUID): Boolean {
        val room = rooms.get(roomId) ?: return false
        runManagedCleanupActionsSafely(
            reportFailure = { failure -> MoreCobblemonContents.LOGGER.error("PvP admin close failed for room $roomId", failure) },
            { sessions.battleIdFor(roomId)?.let(Cobblemon173ManagedBattleTermination::end) },
            { processPendingCompletions(server, force = true) },
            {
                val challenge = sessions.challenge(roomId)
                if (challenge != null && sessions.cancel(roomId, challenge.request.challengerId) is PvpChallengeMutationResult.Applied) {
                    retryableMatches.remove(roomId)
                    notifyClosed(challenge.request, "screen.${MoreCobblemonContents.MOD_ID}.pvp.closed.cancelled")
                }
            },
            { lounge.finish(roomId) },
            {
                val members = rooms.close(roomId)?.memberIds ?: room.memberIds
                val ranked = rankedRooms.remove(roomId)
                if (roomId !in rankedLaunched) rankedMatches.remove(roomId)
                notifyOnlinePlayers(members + ranked?.playerIds.orEmpty(), "admin room close") { player ->
                    player.sendSystemMessage(Component.translatable("command.${MoreCobblemonContents.MOD_ID}.pvp.admin.room.closed_notice"))
                    sendRoomList(player, null)
                    if (ranked != null) sendRankedState(player)
                }
            },
        )
        return true
    }

    enum class AdminKick { NO_ROOM, COMPETITOR, KICKED, FAILED }

    /**
     * Takes [playerId] out of their room. A seated player of a match past the lobby is refused: their match has to
     * be ended or the room closed, or the other player would be left in a half-closed match.
     */
    fun adminKick(playerId: UUID): AdminKick {
        val room = rooms.roomFor(playerId) ?: return AdminKick.NO_ROOM
        val seated = playerId == room.leftPlayerId || playerId == room.rightPlayerId
        if (seated && room.phase != jbro.cobblemon.mcc.internal.pvp.PvpRoomPhase.LOBBY) return AdminKick.COMPETITOR
        val online = onlinePlayers[playerId]
        if (room.phase == jbro.cobblemon.mcc.internal.pvp.PvpRoomPhase.ACTIVE && playerId in room.spectatorIds && online != null) {
            if (exitSpectator(online) != PvpSpectatorExitResult.ACCEPTED) return AdminKick.FAILED
            sendRoomListSafely(online, null, "admin kick")
            return AdminKick.KICKED
        }
        rooms.leave(room.roomId, playerId)?.let { remaining -> pushRoomToMembers(remaining, null) }
        online?.let { sendRoomListSafely(it, null, "admin kick") }
        return AdminKick.KICKED
    }

    /** Arena slots in use, with whether a lounge session still holds each one. */
    fun adminArenas(): List<Triple<UUID, jbro.cobblemon.mcc.internal.pvp.PvpArenaLease, Boolean>> =
        arenas.activeLeases().map { (matchId, lease) -> Triple(matchId, lease, lounge.leaseFor(matchId) != null) }

    /** Frees arena [index] when no lounge session holds it any more; returns false otherwise. */
    fun adminReleaseArena(index: Int): Boolean {
        val (matchId, _, held) = adminArenas().firstOrNull { it.second.index == index } ?: return false
        if (held) return false
        return arenas.release(matchId) != null
    }

    enum class AdminRescue { IN_BATTLE, IN_ACTIVE_ROOM, RETURNED, SPAWN, NOT_IN_LOUNGE, FAILED }

    /** Sends [player] back from the battle lounge: to the point they came from, or to spawn when none is kept. */
    fun adminRescue(player: ServerPlayer): AdminRescue {
        onlinePlayers[player.uuid] = player
        if (BattleRegistry.getBattleByParticipatingPlayerId(player.uuid) != null) return AdminRescue.IN_BATTLE
        if (rooms.roomFor(player.uuid)?.phase == jbro.cobblemon.mcc.internal.pvp.PvpRoomPhase.ACTIVE) return AdminRescue.IN_ACTIVE_ROOM
        if (player.uuid in lounge.pendingReturnPlayerIds()) {
            return if (lounge.restorePending(player.uuid)) AdminRescue.RETURNED else AdminRescue.FAILED
        }
        if (player.serverLevel().dimension() != Cobblemon173PvpLoungeGateway.LEVEL_KEY) return AdminRescue.NOT_IN_LOUNGE
        return if (loungeGateway.restoreToOverworldSpawn(player.uuid)) AdminRescue.SPAWN else AdminRescue.FAILED
    }

    private val adminSource = object : MccAdminSource {
        override val label: Component = Component.translatable("command.${MoreCobblemonContents.MOD_ID}.pvp.admin.label")

        override fun status(server: MinecraftServer): List<Component> = listOf(
            Component.translatable("command.${MoreCobblemonContents.MOD_ID}.pvp.admin.status", rooms.all().size,
                sessions.activeBattles().size, pendingCompletions.size(), arenas.activeLeases().size, lounge.pendingReturnPlayerIds().size),
        )

        override fun pending(server: MinecraftServer): List<MccPendingResult> = pendingCompletions.completions().map {
            MccPendingResult(it.winnerId ?: it.loserId ?: it.matchId, it.battleId,
                if (it.cancelled) "cancelled" else "winner=${it.winnerId} loser=${it.loserId}")
        }

        override fun retryPending(server: MinecraftServer, playerId: UUID?): Int =
            pendingCompletions.retryMatching({ playerId == null || it.winnerId == playerId || it.loserId == playerId }) {
                settleCompletion(server, it)
            }

        override fun dropPending(server: MinecraftServer, playerId: UUID?): Int =
            pendingCompletions.drop { playerId == null || it.winnerId == playerId || it.loserId == playerId }

        override fun busy(server: MinecraftServer, playerId: UUID): Boolean =
            rooms.roomFor(playerId) != null || sessions.challengeFor(playerId) != null ||
                rankedQueue.entryFor(playerId) != null ||
                pendingCompletions.completions().any { it.winnerId == playerId || it.loserId == playerId }
    }

    /**
     * Pulls somebody out of the battle lounge when nothing is left to send them home. Without this a
     * restart, or a regenerated lounge, leaves them standing in empty air with no way back.
     */
    private fun rescueStrandedLoungePlayer(player: ServerPlayer): Boolean {
        val stranded = PvpLoungeRescuePolicy.rescues(
            inLoungeDimension = player.serverLevel().dimension() == Cobblemon173PvpLoungeGateway.LEVEL_KEY,
            hasPendingReturn = player.uuid in lounge.pendingReturnPlayerIds(),
        )
        if (!stranded) return false
        if (!loungeGateway.restoreToOverworldSpawn(player.uuid)) return false
        player.sendSystemMessage(
            Component.translatable("screen.${MoreCobblemonContents.MOD_ID}.pvp.lounge.rescued"),
        )
        MoreCobblemonContents.LOGGER.info("Moved {} out of the battle lounge; no return point was recorded", player.uuid)
        return true
    }

    override fun open(player: ServerPlayer): PvpCommandOutcome {
        onlinePlayers[player.uuid] = player
        if (!ServerPlayNetworking.canSend(player, PvpRoomListStatePayload.TYPE)) {
            return PvpCommandOutcome(PvpCommandStatus.CLIENT_UNSUPPORTED)
        }
        val opened = attemptServerUiOperation(
            reportFailure = { failure ->
                MoreCobblemonContents.LOGGER.error("PvP room list could not be opened for ${player.uuid}", failure)
            },
        ) {
            BattleHubNetworking.sendHeader(player)
            sendRoomList(player, null)
            sendRankedState(player)
            true
        }
        return PvpCommandOutcome(if (opened) PvpCommandStatus.APPLIED else PvpCommandStatus.INTERNAL_FAILURE)
    }

    override fun challenge(
        challenger: ServerPlayer,
        opponent: ServerPlayer,
        format: PvpBattleFormat,
    ): PvpCommandOutcome {
        remember(challenger, opponent)
        if (challenger.uuid == opponent.uuid) return PvpCommandOutcome(PvpCommandStatus.SELF_CHALLENGE)
        if (!supportsSelectionScreen(challenger) || !supportsSelectionScreen(opponent)) {
            return PvpCommandOutcome(PvpCommandStatus.CLIENT_UNSUPPORTED)
        }
        val request = PvpChallengeRequest(UUID.randomUUID(), challenger.uuid, opponent.uuid, format)
        val result = applyPvpInvitationWithNotification(
            invite = { sessions.invite(request) },
            notify = { challenge ->
                opponent.sendSystemMessage(
                    Component.translatable(
                        "command.${MoreCobblemonContents.MOD_ID}.pvp.invited",
                        challenger.scoreboardName,
                        Component.translatable(
                            "screen.${MoreCobblemonContents.MOD_ID}.pvp.format.${challenge.request.format.recordId}",
                        ),
                        challenger.scoreboardName,
                        challenger.scoreboardName,
                    ),
                )
            },
            rollback = { challenge ->
                check(sessions.cancel(challenge.request.challengeId, challenge.request.opponentId) is PvpChallengeMutationResult.Applied) {
                    "New PvP invitation could not be rolled back after its notification failed"
                }
            },
        )
        return when (result) {
            is PvpChallengeMutationResult.Applied -> PvpCommandOutcome(PvpCommandStatus.APPLIED)
            is PvpChallengeMutationResult.Unchanged -> PvpCommandOutcome(PvpCommandStatus.APPLIED)
            is PvpChallengeMutationResult.Rejected -> result.error.toCommandOutcome()
        }
    }

    override fun accept(opponent: ServerPlayer, challenger: ServerPlayer): PvpCommandOutcome {
        remember(opponent, challenger)
        val challenge = sessions.challengeFor(opponent.uuid)
            ?: return PvpCommandOutcome(PvpCommandStatus.UNKNOWN_CHALLENGE)
        if (challenge.request.challengerId != challenger.uuid || challenge.request.opponentId != opponent.uuid) {
            return PvpCommandOutcome(PvpCommandStatus.WRONG_CHALLENGER)
        }
        if (!supportsSelectionScreen(challenger) || !supportsSelectionScreen(opponent)) {
            return PvpCommandOutcome(PvpCommandStatus.CLIENT_UNSUPPORTED)
        }
        try {
            val challengerTeam = Cobblemon173PvpTeamFactory.register(challenger, challenge.request.format)
            if (challengerTeam !is PvpTeamRegistrationResult.Accepted) {
                sessions.cancel(challenge.request.challengeId, opponent.uuid)
                return PvpCommandOutcome(PvpCommandStatus.OPPONENT_TEAM_INVALID)
            }
            val opponentTeam = Cobblemon173PvpTeamFactory.register(opponent, challenge.request.format)
            if (opponentTeam !is PvpTeamRegistrationResult.Accepted) {
                sessions.cancel(challenge.request.challengeId, opponent.uuid)
                return PvpCommandOutcome(PvpCommandStatus.TEAM_INVALID)
            }
            val prepared = sessions.acceptRegisteredMatch(
                challenge.request.challengeId,
                opponent.uuid,
                challengerTeam.team,
                opponentTeam.team,
            )
            if (prepared !is PvpChallengeMutationResult.Applied) return prepared.toCommandOutcome()
        } catch (failure: Throwable) {
            try {
                sessions.cancel(challenge.request.challengeId, opponent.uuid)
            } catch (cleanupFailure: Throwable) {
                if (failure !== cleanupFailure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
        protectProvisionalPvpStateNotification(
            rollback = {
                runManagedCleanupActions(
                    { sessions.cancel(challenge.request.challengeId, opponent.uuid) },
                    {
                        notifyClosed(
                            challenge.request,
                            "screen.${MoreCobblemonContents.MOD_ID}.pvp.closed.cancelled",
                        )
                    },
                )
            },
        ) {
            sendState(challenger, null)
            sendState(opponent, null)
        }
        return PvpCommandOutcome(PvpCommandStatus.APPLIED)
    }

    override fun decline(opponent: ServerPlayer, challenger: ServerPlayer): PvpCommandOutcome {
        remember(opponent, challenger)
        val challenge = sessions.challengeFor(opponent.uuid)
            ?: return PvpCommandOutcome(PvpCommandStatus.UNKNOWN_CHALLENGE)
        if (challenge.request.challengerId != challenger.uuid || challenge.request.opponentId != opponent.uuid) {
            return PvpCommandOutcome(PvpCommandStatus.WRONG_CHALLENGER)
        }
        return sessions.reject(challenge.request.challengeId, opponent.uuid).toCommandOutcome()
    }

    override fun cancel(player: ServerPlayer): PvpCommandOutcome {
        onlinePlayers[player.uuid] = player
        val challenge = sessions.challengeFor(player.uuid)
            ?: return PvpCommandOutcome(PvpCommandStatus.UNKNOWN_CHALLENGE)
        val result = sessions.cancel(challenge.request.challengeId, player.uuid)
        if (result is PvpChallengeMutationResult.Applied) {
            forfeitRankedSelection(player.server, challenge.request.challengeId, player.uuid)
            retryableMatches.remove(challenge.request.challengeId)
            notifyClosed(challenge.request, "screen.${MoreCobblemonContents.MOD_ID}.pvp.closed.cancelled")
            finishRoom(challenge.request.challengeId)
        }
        return result.toCommandOutcome()
    }

    private fun handleRoomIntent(player: ServerPlayer, intent: PvpRoomIntent) {
        // Coming into a room from outside is what a switched-off PvP refuses; members carry on.
        if (intent is PvpRoomIntent.Create || intent is PvpRoomIntent.Join || intent is PvpRoomIntent.Observe) {
            val paused = MccContentSwitches.check(player.server, player.uuid, ManagedBattleContentIds.PVP)
            if (paused is ContentAccessDecision.Denied) {
                ServerPlayNetworking.send(player, PvpRoomRejectedPayload(intent.requestId, paused.reasonKey, paused.arguments))
                return
            }
        }
        // A ranked match runs in a room nobody sees; nothing reaches it through the room controls.
        val ownRoom = rooms.roomFor(player.uuid)
        if (ownRoom != null && ownRoom.roomId in rankedRooms && intent !is PvpRoomIntent.Refresh) {
            rejectRoom(player, intent.requestId, PvpRoomError.PLAYER_BUSY)
            return
        }
        if (intent.targetRoomId()?.let { it in rankedRooms } == true) {
            rejectRoom(player, intent.requestId, PvpRoomError.UNKNOWN_ROOM)
            return
        }
        // Going into a room gives up a place in the ranked queue.
        if ((intent is PvpRoomIntent.Create || intent is PvpRoomIntent.Join) && rankedQueue.leave(player.uuid) != null) {
            sendRankedState(player)
            pushRankedQueueSizes()
        }
        when (intent) {
            is PvpRoomIntent.Refresh -> {
                when (PvpRoomRefreshContract.response(hasRoomMembership = rooms.roomFor(player.uuid) != null)) {
                    PvpRoomRefreshResponse.ROOM_LIST -> sendRoomList(player, intent.requestId)
                }
            }
            is PvpRoomIntent.Create -> {
                val created = rooms.create(player.uuid, intent.settings).room
                sendRoom(player, created, intent.requestId)
            }
            is PvpRoomIntent.Join -> joinRoom(player, intent)
            is PvpRoomIntent.Leave -> {
                val current = rooms.get(intent.roomId)
                if (current == null) {
                    rejectRoom(player, intent.requestId, PvpRoomError.NOT_MEMBER)
                    return
                }
                val error = current.leaveRequestError(player.uuid)
                if (error != null) {
                    rejectRoom(player, intent.requestId, error)
                    return
                }
                if (current.phase == jbro.cobblemon.mcc.internal.pvp.PvpRoomPhase.ACTIVE &&
                    player.uuid in current.spectatorIds
                ) {
                    when (exitSpectator(player)) {
                        PvpSpectatorExitResult.ACCEPTED ->
                            sendRoomListSafely(player, intent.requestId, "spectator room leave response")
                        PvpSpectatorExitResult.INVALID_STATE ->
                            rejectRoom(player, intent.requestId, PvpRoomError.INVALID_PHASE)
                        PvpSpectatorExitResult.INTERNAL_FAILURE ->
                            rejectRoom(player, intent.requestId, PvpRoomError.INVALID_PHASE)
                    }
                    return
                }
                val remaining = rooms.leave(intent.roomId, player.uuid)
                if (remaining != null) {
                    pushRoomToMembers(remaining, null)
                    if (remaining.phase == jbro.cobblemon.mcc.internal.pvp.PvpRoomPhase.TEAM_PREVIEW) {
                        pushSpectatorPreview(remaining)
                    }
                }
                sendRoomListSafely(player, intent.requestId, "room leave response")
            }
            is PvpRoomIntent.ClaimSeat ->
                applyRoomMutation(player, intent.requestId, rooms.claimSeat(intent.roomId, player.uuid, intent.side), pushAll = true)
            is PvpRoomIntent.Observe ->
                applyRoomMutation(player, intent.requestId, rooms.observe(intent.roomId, player.uuid), pushAll = true)
            is PvpRoomIntent.UpdateSettings ->
                applyRoomMutation(
                    player,
                    intent.requestId,
                    rooms.updateSettings(intent.roomId, player.uuid, intent.settings),
                    pushAll = true,
                )
            is PvpRoomIntent.Invite -> {
                val result = when {
                    // The host's list can trail a logout or a match by a moment; check the target now.
                    intent.targetId !in onlinePlayers -> PvpRoomMutation.Rejected(PvpRoomError.TARGET_OFFLINE)
                    sessions.challengeFor(intent.targetId) != null -> PvpRoomMutation.Rejected(PvpRoomError.TARGET_BUSY)
                    else -> rooms.invite(intent.roomId, player.uuid, intent.targetId)
                }
                if (result is PvpRoomMutation.Applied) {
                    onlinePlayers[intent.targetId]?.let { target ->
                        if (ServerPlayNetworking.canSend(target, PvpRoomInvitePayload.TYPE)) {
                            ServerPlayNetworking.send(
                                target,
                                PvpRoomInvitePayload(intent.roomId, player.scoreboardName, result.room.settings),
                            )
                        } else {
                            target.sendSystemMessage(
                                Component.translatable(
                                    "screen.${MoreCobblemonContents.MOD_ID}.pvp.room.invited.fallback",
                                    player.scoreboardName,
                                ),
                            )
                        }
                    }
                }
                applyRoomMutation(player, intent.requestId, result)
            }
            is PvpRoomIntent.DeclineInvite -> {
                when (val result = rooms.declineInvite(intent.roomId, player.uuid)) {
                    is PvpRoomMutation.Applied -> {
                        player.sendSystemMessage(
                            Component.translatable("screen.${MoreCobblemonContents.MOD_ID}.pvp.room.invite.declined"),
                        )
                        // The host's picker drops its "invited" mark.
                        pushRoomToMembers(result.room, null)
                    }
                    is PvpRoomMutation.Rejected -> rejectRoom(player, intent.requestId, result.error)
                }
            }
            is PvpRoomIntent.TransferHost ->
                applyRoomMutation(
                    player,
                    intent.requestId,
                    rooms.transferHost(intent.roomId, player.uuid, intent.targetId),
                    pushAll = true,
                )
            is PvpRoomIntent.Start -> startRoom(player, intent)
        }
    }

    private fun joinRoom(player: ServerPlayer, intent: PvpRoomIntent.Join) {
        val wasMember = player.uuid in rooms.get(intent.roomId)?.memberIds.orEmpty()
        val joined = rooms.join(intent.roomId, player.uuid)
        if (joined !is PvpRoomMutation.Applied) {
            applyRoomMutation(player, intent.requestId, joined)
            return
        }
        if (!wasMember && joined.room.phase == jbro.cobblemon.mcc.internal.pvp.PvpRoomPhase.ACTIVE) {
            val targetId = joined.room.leftPlayerId
            if (targetId == null || !lounge.addSpectator(joined.room.roomId, player.uuid, targetId)) {
                val battleId = sessions.battleIdFor(joined.room.roomId)
                if (battleId == null || BattleRegistry.getBattle(battleId) == null) {
                    runManagedCleanupActionsSafely(
                        reportFailure = { failure ->
                            MoreCobblemonContents.LOGGER.error(
                                "Could not reconcile stale PvP match ${joined.room.roomId}",
                                failure,
                            )
                        },
                        { battleId?.let { sessions.cancelBattle(joined.room.roomId, it) } },
                    )
                    finishRoom(joined.room.roomId)
                    rooms.get(joined.room.roomId)?.let { recovered ->
                        if (player.uuid in recovered.memberIds) {
                            pushRoomToMembers(recovered, intent.requestId)
                            return
                        }
                    }
                }
                rooms.leave(joined.room.roomId, player.uuid)
                rejectRoom(player, intent.requestId, PvpRoomError.INVALID_PHASE)
                return
            }
        }
        pushRoomToMembers(joined.room, intent.requestId)
        if (joined.room.phase == jbro.cobblemon.mcc.internal.pvp.PvpRoomPhase.TEAM_PREVIEW &&
            player.uuid in joined.room.spectatorIds
        ) {
            sendSpectatorPreview(player, joined.room)
        }
    }

    private fun startRoom(player: ServerPlayer, intent: PvpRoomIntent.Start) {
        val room = rooms.get(intent.roomId)
        if (room == null) {
            rejectRoom(player, intent.requestId, PvpRoomError.UNKNOWN_ROOM)
            return
        }
        if (room.hostId != player.uuid) {
            rejectRoom(player, intent.requestId, PvpRoomError.HOST_ONLY)
            return
        }
        val leftId = room.leftPlayerId
        val rightId = room.rightPlayerId
        if (leftId == null || rightId == null) {
            rejectRoom(player, intent.requestId, PvpRoomError.SEATS_INCOMPLETE)
            return
        }
        val left = onlinePlayers[leftId]
        val right = onlinePlayers[rightId]
        if (left == null || right == null || !supportsSelectionScreen(left) || !supportsSelectionScreen(right)) {
            rejectRoom(player, intent.requestId, PvpRoomError.INVALID_PHASE)
            return
        }
        val leftTeam = Cobblemon173PvpTeamFactory.register(left, room.settings.format, room.settings.speciesClause, room.settings.itemClause)
        val rightTeam = Cobblemon173PvpTeamFactory.register(right, room.settings.format, room.settings.speciesClause, room.settings.itemClause)
        if (leftTeam !is PvpTeamRegistrationResult.Accepted || rightTeam !is PvpTeamRegistrationResult.Accepted) {
            // Name the seat and the broken rule, so the players know whose party to fix.
            val (offender, rejected) = if (leftTeam is PvpTeamRegistrationResult.Rejected) {
                left to leftTeam
            } else {
                right to rightTeam as PvpTeamRegistrationResult.Rejected
            }
            ServerPlayNetworking.send(
                player,
                teamInvalidRejection(intent.requestId, offender.scoreboardName, room.settings.format, rejected.issues),
            )
            return
        }
        val request = PvpChallengeRequest(
            challengeId = room.roomId,
            challengerId = leftId,
            opponentId = rightId,
            format = room.settings.format,
            enabledMechanics = room.settings.immutableEnabledMechanics,
        )
        if (!sessions.prepareRoomMatch(request, leftTeam.team, rightTeam.team)) {
            rejectRoom(player, intent.requestId, PvpRoomError.INVALID_PHASE)
            return
        }
        val started = rooms.startPreview(room.roomId, player.uuid)
        if (started !is PvpRoomMutation.Applied) {
            sessions.cancel(room.roomId, leftId)
            applyRoomMutation(player, intent.requestId, started)
            return
        }
        pushRoomToMembers(started.room, intent.requestId)
        protectProvisionalPvpStateNotification(
            rollback = {
                var restoredRoom: PvpRoomView? = null
                runManagedCleanupActions(
                    { sessions.cancel(room.roomId, leftId) },
                    { restoredRoom = rooms.finishMatch(room.roomId) },
                    {
                        notifyClosed(
                            request,
                            "screen.${MoreCobblemonContents.MOD_ID}.pvp.closed.cancelled",
                        )
                    },
                    { restoredRoom?.let { pushRoomToMembers(it, null, reopen = true) } },
                )
            },
        ) {
            sendState(left, null)
            sendState(right, null)
        }
        pushSpectatorPreview(started.room)
    }

    private fun applyRoomMutation(
        player: ServerPlayer,
        requestId: UUID,
        result: PvpRoomMutation,
        pushAll: Boolean = false,
    ) {
        when (result) {
            is PvpRoomMutation.Applied -> {
                if (pushAll) pushRoomToMembers(result.room, requestId) else sendRoom(player, result.room, requestId)
            }
            is PvpRoomMutation.Rejected -> rejectRoom(player, requestId, result.error)
        }
    }

    private fun sendRoomList(player: ServerPlayer, requestId: UUID?) {
        val state = rooms.listStateFor(player.uuid, requestId, ::summaryView)
        // Ranked matches' rooms stay out of the list, and a player in one is not in a room as far as the client knows.
        ServerPlayNetworking.send(player, state.copy(
            rooms = state.rooms.filter { it.roomId !in rankedRooms },
            memberRoomId = state.memberRoomId?.takeIf { it !in rankedRooms },
        ))
    }

    private fun sendRoomListSafely(player: ServerPlayer, requestId: UUID?, operation: String) {
        runManagedCleanupActionsSafely(
            reportFailure = { failure ->
                MoreCobblemonContents.LOGGER.error("PvP $operation failed for ${player.uuid}", failure)
            },
            { sendRoomList(player, requestId) },
        )
    }

    private fun sendRoom(player: ServerPlayer, room: PvpRoomView, requestId: UUID?, reopen: Boolean = false) {
        if (room.roomId in rankedRooms) return
        ServerPlayNetworking.send(player, PvpRoomStatePayload(requestId, clientView(room), reopen))
    }

    private fun pushRoomToMembers(room: PvpRoomView, requestId: UUID?, reopen: Boolean = false) {
        notifyOnlinePlayers(room.memberIds, "room update") { player -> sendRoom(player, room, requestId, reopen) }
    }

    /** Resends every lobby, whose invite lists name who is online and free, after someone joins or leaves the server. */
    private fun pushLobbyRooms(except: UUID? = null) {
        rooms.all().filter { it.phase == jbro.cobblemon.mcc.internal.pvp.PvpRoomPhase.LOBBY && it.roomId != except }
            .forEach { pushRoomToMembers(it, null) }
    }

    private fun pushRoomToSpectators(room: PvpRoomView, requestId: UUID?) {
        notifyOnlinePlayers(room.spectatorIds, "spectator room update") { player -> sendRoom(player, room, requestId) }
    }

    private fun pushSpectatorPreview(room: PvpRoomView) {
        notifyOnlinePlayers(room.spectatorIds, "spectator preview") { player -> sendSpectatorPreview(player, room) }
    }

    private fun notifyOnlinePlayers(
        playerIds: Iterable<UUID>,
        notification: String,
        send: (ServerPlayer) -> Unit,
    ) {
        runManagedCleanupForEachSafely(
            items = playerIds,
            reportFailure = { playerId, failure ->
                MoreCobblemonContents.LOGGER.error("PvP $notification failed for $playerId", failure)
            },
        ) { playerId ->
            onlinePlayers[playerId]?.let(send)
        }
    }

    private fun sendSpectatorPreview(player: ServerPlayer, room: PvpRoomView) {
        val preview = sessions.spectatorPreview(room.roomId) ?: return
        val state = PvpSelectionViewState(
            matchId = preview.matchId,
            format = preview.format,
            opponentName = onlinePlayers[preview.rightPlayerId]?.scoreboardName ?: preview.rightPlayerId.toString(),
            ownParty = emptyList(),
            opponentParty = emptyList(),
            selectedPokemonIds = emptySet(),
            selectionDeadlineEpochMillis = sessions.entryDeadlineFor(preview.matchId) ?: System.currentTimeMillis(),
            waitingForOpponent = true,
            playerOnLeft = true,
            leftPlayerName = onlinePlayers[preview.leftPlayerId]?.scoreboardName ?: preview.leftPlayerId.toString(),
            rightPlayerName = onlinePlayers[preview.rightPlayerId]?.scoreboardName ?: preview.rightPlayerId.toString(),
            spectators = room.spectatorIds.map { spectatorId ->
                PvpSelectionSpectator(spectatorId, onlinePlayers[spectatorId]?.scoreboardName ?: spectatorId.toString())
            },
            spectatorMode = true,
            spectatorLeftParty = preview.leftTeam.members.map { member ->
                PvpSelectionOpponentSlot(member.speciesId, member.formId)
            },
            spectatorRightParty = preview.rightTeam.members.map { member ->
                PvpSelectionOpponentSlot(member.speciesId, member.formId)
            },
        )
        ServerPlayNetworking.send(player, PvpSelectionStatePayload(null, state))
    }

    private fun exitSpectator(player: ServerPlayer): PvpSpectatorExitResult {
        val room = rooms.roomFor(player.uuid)
        if (room == null) {
            return if (recoverUntrackedSpectator(player)) {
                PvpSpectatorExitResult.ACCEPTED
            } else {
                PvpSpectatorExitResult.INTERNAL_FAILURE
            }
        }
        if (player.uuid !in room.spectatorIds) return PvpSpectatorExitResult.INVALID_STATE

        var remaining: PvpRoomView? = null
        var roomLeaveCompleted = false
        var stateCleanupFailed = false
        runManagedCleanupActionsSafely(
            reportFailure = { failure ->
                stateCleanupFailed = true
                MoreCobblemonContents.LOGGER.error("PvP spectator exit cleanup failed for ${player.uuid}", failure)
            },
            {
                if (room.phase == jbro.cobblemon.mcc.internal.pvp.PvpRoomPhase.ACTIVE &&
                    !lounge.removeSpectator(room.roomId, player.uuid)
                ) {
                    if (!recoverUntrackedSpectator(player)) stateCleanupFailed = true
                }
            },
            {
                remaining = rooms.leave(room.roomId, player.uuid)
                roomLeaveCompleted = true
            },
        )
        runManagedCleanupActionsSafely(
            reportFailure = { failure ->
                MoreCobblemonContents.LOGGER.error("PvP spectator exit notification failed for ${player.uuid}", failure)
            },
            {
                remaining?.let { updatedRoom ->
                    pushRoomToMembers(updatedRoom, null)
                    if (updatedRoom.phase == jbro.cobblemon.mcc.internal.pvp.PvpRoomPhase.TEAM_PREVIEW) {
                        pushSpectatorPreview(updatedRoom)
                    }
                }
            },
        )
        return if (roomLeaveCompleted && !stateCleanupFailed) {
            PvpSpectatorExitResult.ACCEPTED
        } else {
            PvpSpectatorExitResult.INTERNAL_FAILURE
        }
    }

    private fun reportPvpFailureSafely(message: String, failure: Throwable) {
        reportManagedCleanupFailureSafely(failure) {
            MoreCobblemonContents.LOGGER.error(message, it)
        }
    }

    private fun rejectFailedSelectionRequest(
        player: ServerPlayer,
        intent: PvpSelectionIntent,
        failure: Throwable,
    ) {
        runManagedCleanupActionsSafely(
            reportFailure = {},
            { MoreCobblemonContents.LOGGER.error("PvP selection request failed for ${player.uuid}", failure) },
            { reject(player, intent, "screen.${MoreCobblemonContents.MOD_ID}.pvp.error.internal_failure") },
        )
    }

    private fun rejectFailedRoomRequest(player: ServerPlayer, requestId: UUID, failure: Throwable) {
        runManagedCleanupActionsSafely(
            reportFailure = {},
            { MoreCobblemonContents.LOGGER.error("PvP room request failed for ${player.uuid}", failure) },
            { rejectRoom(player, requestId, PvpRoomError.INVALID_PHASE) },
        )
    }

    private enum class PvpSpectatorExitResult {
        ACCEPTED,
        INVALID_STATE,
        INTERNAL_FAILURE,
    }

    private fun recoverUntrackedSpectator(player: ServerPlayer): Boolean = attemptUntrackedPvpSpectatorRecovery(
        hasPendingReturn = player.uuid in lounge.pendingReturnPlayerIds(),
        inLoungeDimension = player.serverLevel().dimension() == Cobblemon173PvpLoungeGateway.LEVEL_KEY,
        restorePending = { lounge.restorePending(player.uuid) },
        rescueStranded = { rescueStrandedLoungePlayer(player) },
        notifyInactive = {
            if (ServerPlayNetworking.canSend(player, PvpLoungeSpectatorStatePayload.TYPE)) {
                ServerPlayNetworking.send(player, PvpLoungeSpectatorStatePayload(false))
            }
        },
    )

    /**
     * Tears down the arena a match used and returns its room to the lobby, keeping the group together
     * for a rematch. Only a room that no longer exists falls back to the room list.
     */
    private fun finishRoom(roomId: UUID) {
        if (roomId in rankedRooms) {
            finishRankedRoom(roomId)
            return
        }
        var room: PvpRoomView? = null
        runManagedCleanupActionsSafely(
            reportFailure = { failure ->
                MoreCobblemonContents.LOGGER.error("PvP room finish cleanup failed for room $roomId", failure)
            },
            { lounge.finish(roomId) },
            { room = rooms.finishMatch(roomId) },
            {
                val finishedRoom = room
                if (finishedRoom == null) {
                    rooms.close(roomId)?.memberIds?.let { memberIds ->
                        notifyOnlinePlayers(memberIds, "room list refresh") { player -> sendRoomList(player, null) }
                    }
                } else {
                    pushRoomToMembers(finishedRoom, null, reopen = true)
                }
            },
        )
    }

    private fun summaryView(room: PvpRoomView) = PvpRoomSummaryView(
        roomId = room.roomId,
        host = memberView(room.hostId),
        settings = room.settings,
        phase = room.phase,
        leftPlayer = room.leftPlayerId?.let(::memberView),
        rightPlayer = room.rightPlayerId?.let(::memberView),
        spectatorCount = room.spectatorIds.size,
    )

    private fun clientView(room: PvpRoomView) = PvpRoomClientView(
        roomId = room.roomId,
        hostId = room.hostId,
        settings = room.settings,
        phase = room.phase,
        leftPlayer = room.leftPlayerId?.let(::memberView),
        rightPlayer = room.rightPlayerId?.let(::memberView),
        spectators = room.spectatorIds.map(::memberView),
        inviteCandidates = pvpInviteCandidates(onlinePlayers.keys, room.memberIds, ::memberView) { playerId ->
            rooms.roomFor(playerId) != null || sessions.challengeFor(playerId) != null
        },
        invitedIds = room.invitedIds,
    )

    /**
     * The player tracked as online, read back from the server's player list. [onlinePlayers] decides who counts as
     * online (a disconnecting player leaves it first), but the object it holds can be one a respawn already replaced.
     */
    private fun livePlayer(playerId: UUID): ServerPlayer? {
        val tracked = onlinePlayers[playerId] ?: return null
        return tracked.server.playerList.getPlayer(playerId) ?: tracked
    }

    private fun memberView(playerId: UUID) = PvpRoomMemberView(
        playerId,
        onlinePlayers[playerId]?.scoreboardName ?: playerId.toString(),
    )

    private fun teamInvalidRejection(
        requestId: UUID,
        playerName: String,
        format: PvpBattleFormat,
        issues: Set<PvpTeamIssue>,
    ): PvpRoomRejectedPayload {
        val (key, args) = teamInvalidMessage(playerName, format, issues)
        return PvpRoomRejectedPayload(requestId, key, args)
    }

    /** The message naming [playerName] and the first rule their party breaks. */
    private fun teamInvalidMessage(playerName: String, format: PvpBattleFormat, issues: Set<PvpTeamIssue>): Pair<String, List<String>> {
        val prefix = "screen.${MoreCobblemonContents.MOD_ID}.pvp.room.error.team_invalid"
        return when {
            PvpTeamIssue.WRONG_TEAM_SIZE in issues -> "$prefix.team_size" to
                listOf(playerName, format.registrationRange.first.toString(), format.registrationRange.last.toString())
            PvpTeamIssue.DUPLICATE_SPECIES in issues -> "$prefix.duplicate_species" to listOf(playerName)
            PvpTeamIssue.DUPLICATE_HELD_ITEM in issues -> "$prefix.duplicate_held_item" to listOf(playerName)
            else -> "$prefix.player" to listOf(playerName)
        }
    }

    private fun rejectRoom(player: ServerPlayer, requestId: UUID, error: PvpRoomError) {
        ServerPlayNetworking.send(
            player,
            PvpRoomRejectedPayload(
                requestId,
                "screen.${MoreCobblemonContents.MOD_ID}.pvp.room.error.${error.name.lowercase()}",
            ),
        )
    }

    private fun handleIntent(player: ServerPlayer, intent: PvpSelectionIntent) {
        val view = sessions.viewFor(player.uuid)
        if (view == null || view.matchId != intent.matchId) {
            reject(player, intent, "screen.${MoreCobblemonContents.MOD_ID}.pvp.error.session_not_found")
            return
        }
        when (intent) {
            is PvpSelectionIntent.Submit -> {
                val selected = sessions.select(intent.matchId, player.uuid, intent.pokemonIds)
                handleLaunchResult(
                    player,
                    intent,
                    if (selected == PvpSelectionMutation.SELECTION_STORED) {
                        sessions.ready(intent.matchId, player.uuid)
                    } else {
                        selected
                    },
                )
            }
            is PvpSelectionIntent.Retry -> handleLaunchResult(
                player,
                intent,
                sessions.launchReady(intent.matchId),
            )
            is PvpSelectionIntent.Cancel -> {
                val challenge = sessions.challengeFor(player.uuid)
                val result = sessions.cancel(intent.matchId, player.uuid)
                if (result is PvpChallengeMutationResult.Applied && challenge != null) {
                    forfeitRankedSelection(player.server, intent.matchId, player.uuid)
                    retryableMatches.remove(intent.matchId)
                    notifyClosed(challenge.request, "screen.${MoreCobblemonContents.MOD_ID}.pvp.closed.cancelled")
                    finishRoom(intent.matchId)
                } else {
                    reject(player, intent, "screen.${MoreCobblemonContents.MOD_ID}.pvp.error.invalid_state")
                }
            }
            is PvpSelectionIntent.Unready -> {
                if (sessions.unready(intent.matchId, player.uuid)) {
                    sendState(player, intent.requestId)
                } else {
                    reject(player, intent, "screen.${MoreCobblemonContents.MOD_ID}.pvp.error.invalid_state")
                }
            }
        }
    }

    private fun handleLaunchResult(
        player: ServerPlayer,
        intent: PvpSelectionIntent,
        result: PvpSelectionMutation,
    ) {
        when (result) {
            PvpSelectionMutation.WAITING_FOR_OPPONENT -> sendState(player, intent.requestId)
            PvpSelectionMutation.SELECTION_STORED -> sendState(player, intent.requestId)
            PvpSelectionMutation.BATTLE_STARTED -> {
                markRankedLaunched(intent.matchId)
                val challenge = sessions.challengeFor(player.uuid) ?: return
                retryableMatches.remove(intent.matchId)
                pushActiveRoomToSpectators(intent.matchId)
                notifyClosed(challenge.request, "screen.${MoreCobblemonContents.MOD_ID}.pvp.closed.battle_started")
            }
            PvpSelectionMutation.BATTLE_UNAVAILABLE -> {
                retryableMatches += intent.matchId
                sessions.challenge(intent.matchId)?.request?.let(::sendStateToBoth)
            }
            PvpSelectionMutation.ENTRY_EXPIRED -> processEntryTimeouts()
            PvpSelectionMutation.INVALID_SELECTION ->
                reject(player, intent, "screen.${MoreCobblemonContents.MOD_ID}.pvp.error.invalid_selection")
            PvpSelectionMutation.UNKNOWN_MATCH ->
                reject(player, intent, "screen.${MoreCobblemonContents.MOD_ID}.pvp.error.session_not_found")
            else -> reject(player, intent, "screen.${MoreCobblemonContents.MOD_ID}.pvp.error.invalid_state")
        }
    }

    private fun sendState(player: ServerPlayer, requestId: UUID?) {
        val view = sessions.viewFor(player.uuid) ?: return
        val opponent = onlinePlayers[view.opponentId]
        val selected = view.selection?.members?.map { it.pokemonId }?.toSet().orEmpty()
        val room = rooms.get(view.matchId)
        val challenge = sessions.challenge(view.matchId)?.request
        val playerOnLeft = room?.leftPlayerId?.let { it == player.uuid }
            ?: challenge?.challengerId?.let { it == player.uuid }
            ?: true
        val leftPlayerId = room?.leftPlayerId ?: challenge?.challengerId
        val rightPlayerId = room?.rightPlayerId ?: challenge?.opponentId
        val state = PvpSelectionViewState(
            matchId = view.matchId,
            format = view.format,
            opponentName = opponent?.scoreboardName ?: view.opponentId.toString(),
            ownParty = view.ownTeam.members.map { member ->
                PvpSelectionPartySlot(
                    pokemonId = member.pokemonId,
                    speciesId = member.speciesId,
                    heldItemId = member.heldItemId,
                    originalLevel = member.level,
                    battleLevel = member.battleLevel,
                    formId = member.formId,
                )
            },
            opponentParty = view.opponentPreview.members.map { member ->
                PvpSelectionOpponentSlot(member.speciesId, member.formId)
            },
            selectedPokemonIds = selected,
            selectionDeadlineEpochMillis = sessions.entryDeadlineFor(view.matchId) ?: System.currentTimeMillis(),
            waitingForOpponent = view.ready,
            battleStartRetryAvailable = view.matchId in retryableMatches,
            playerOnLeft = playerOnLeft,
            leftPlayerName = leftPlayerId?.let { onlinePlayers[it]?.scoreboardName ?: it.toString() }.orEmpty(),
            rightPlayerName = rightPlayerId?.let { onlinePlayers[it]?.scoreboardName ?: it.toString() }.orEmpty(),
            spectators = room?.spectatorIds.orEmpty().map { spectatorId ->
                PvpSelectionSpectator(spectatorId, onlinePlayers[spectatorId]?.scoreboardName ?: spectatorId.toString())
            },
        )
        ServerPlayNetworking.send(player, PvpSelectionStatePayload(requestId, state))
    }

    private fun reject(player: ServerPlayer, intent: PvpSelectionIntent, messageKey: String) {
        ServerPlayNetworking.send(player, PvpSelectionRejectedPayload(intent.requestId, intent.matchId, messageKey))
    }

    /**
     * Closes the team preview for both players and for the room's spectators, who watch it read-only. A room match's
     * id is its room id; call this while the room still lists its spectators.
     */
    private fun notifyClosed(request: PvpChallengeRequest, messageKey: String) {
        val recipients = linkedSetOf(request.challengerId, request.opponentId)
        rooms.get(request.challengeId)?.let { room -> recipients += room.spectatorIds }
        notifyOnlinePlayers(recipients, "selection close") { player ->
            if (ServerPlayNetworking.canSend(player, PvpSelectionClosedPayload.TYPE)) {
                ServerPlayNetworking.send(player, PvpSelectionClosedPayload(request.challengeId, messageKey))
            }
        }
    }

    private fun sendStateToBoth(request: PvpChallengeRequest) {
        notifyOnlinePlayers(listOf(request.challengerId, request.opponentId), "selection state") { player ->
            sendState(player, null)
        }
    }

    private fun processEntryTimeouts() {
        runManagedCleanupForEachSafely(
            items = sessions.expireEntrySelections(),
            reportFailure = { resolution, failure ->
                MoreCobblemonContents.LOGGER.error("PvP entry timeout cleanup failed for ${resolution.matchId}", failure)
            },
        ) { resolution ->
            val request = sessions.challenge(resolution.matchId)?.request ?: return@runManagedCleanupForEachSafely
            when (resolution.launchResult) {
                PvpSelectionMutation.BATTLE_STARTED -> {
                    markRankedLaunched(resolution.matchId)
                    retryableMatches.remove(resolution.matchId)
                    pushActiveRoomToSpectators(resolution.matchId)
                    notifyClosed(request, "screen.${MoreCobblemonContents.MOD_ID}.pvp.closed.battle_started")
                }
                PvpSelectionMutation.BATTLE_UNAVAILABLE -> {
                    retryableMatches += resolution.matchId
                    sendStateToBoth(request)
                }
                else -> sendStateToBoth(request)
            }
        }
    }

    private fun processPendingCompletions(server: MinecraftServer, force: Boolean = false) {
        pendingCompletions.retryDue(force) { pending ->
            settleCompletion(server, pending)
        }
    }

    private fun settleCompletion(server: MinecraftServer, pending: PendingPvpCompletion): Boolean =
        attemptPvpCompletionSettlement(
            settle = {
                if (sessions.isLaunchPending(pending.matchId)) return@attemptPvpCompletionSettlement false
                var recordedFormat: PvpBattleFormat? = null
                // A ranked match stays known until its result settles, whatever became of its room meanwhile.
                val ranked = pending.matchId in rankedMatches
                var rankedResult: PvpRankedResult? = null
                val accepted = transitionPvpBattleLifecycle(
                    transition = {
                        if (pending.cancelled) {
                            sessions.cancelBattle(pending.matchId, pending.battleId)
                        } else {
                            sessions.completeBattle(
                                pending.matchId,
                                pending.battleId,
                                checkNotNull(pending.winnerId),
                                checkNotNull(pending.loserId),
                                PvpBattleCompletionSink { winnerId, loserId, format ->
                                    recordedFormat = format
                                    PvpBattleRecordService { completions ->
                                        BattleRecordService.recordCompletedBattles(server, completions)
                                    }.recordResult(winnerId, loserId, format)
                                        .also { PvpMatchHistory.record(server, pending.battleId, winnerId, loserId, format.recordId) }
                                        .also {
                                            if (ranked) {
                                                rankedResult = PvpRankedHistory.settle(pending.battleId, format,
                                                    winnerId, MccAdminArguments.name(server, winnerId),
                                                    loserId, MccAdminArguments.name(server, loserId))
                                            }
                                        }
                                },
                            )
                        }
                    },
                    isStillPending = { sessions.battleIdFor(pending.matchId) != null },
                    cleanup = {
                        retryableMatches.remove(pending.matchId)
                        finishRoom(pending.matchId)
                    },
                )
                if (accepted && !pending.cancelled) {
                    val winner = server.playerList.getPlayer(checkNotNull(pending.winnerId))
                    val loser = server.playerList.getPlayer(checkNotNull(pending.loserId))
                    fun name(id: UUID) = Component.literal(jbro.cobblemon.mcc.internal.command.MccAdminArguments.name(server, id))
                    winner?.let { jbro.cobblemon.mcc.api.presentation.BattleResultNotices.victory(it, name(pending.loserId)) }
                    loser?.let { jbro.cobblemon.mcc.api.presentation.BattleResultNotices.defeat(it, name(pending.winnerId)) }
                    recordedFormat?.let { format -> broadcastResult(server, pending, format, ranked) }
                    rankedResult?.let { result -> tellRatingChange(server, result) }
                }
                if (!accepted) {
                    MoreCobblemonContents.LOGGER.warn(
                        "Dropping stale PvP completion retry for match {} and battle {}",
                        pending.matchId,
                        pending.battleId,
                    )
                }
                rankedMatches.remove(pending.matchId)
                rankedLaunched.remove(pending.matchId)
                true
            },
            reportFailure = { failure ->
                MoreCobblemonContents.LOGGER.error(
                    "PvP battle-end settlement failed for match {} and battle {}; retrying in {} ms",
                    pending.matchId,
                    pending.battleId,
                    PVP_COMPLETION_RETRY_MILLIS,
                    failure,
                )
            },
        )

    /** Tells the whole server who beat whom, by display name so Styled Nicknames' nicknames show. */
    private fun broadcastResult(server: MinecraftServer, pending: PendingPvpCompletion, format: PvpBattleFormat, ranked: Boolean) {
        fun name(id: UUID): Component = pending.displayNames[id]
            ?: server.playerList.getPlayer(id)?.displayName
            ?: Component.literal(jbro.cobblemon.mcc.internal.command.MccAdminArguments.name(server, id))
        server.playerList.broadcastSystemMessage(
            Component.translatable(
                if (ranked) "chat.${MoreCobblemonContents.MOD_ID}.pvp.ranked_result" else "chat.${MoreCobblemonContents.MOD_ID}.pvp.result",
                name(checkNotNull(pending.winnerId)),
                name(checkNotNull(pending.loserId)),
                Component.translatable("screen.${MoreCobblemonContents.MOD_ID}.pvp.format.${format.recordId}"),
            ),
            false,
        )
    }

    private fun pushActiveRoomToSpectators(matchId: UUID) {
        rooms.get(matchId)
            ?.takeIf { room -> room.phase == jbro.cobblemon.mcc.internal.pvp.PvpRoomPhase.ACTIVE }
            ?.let { room -> pushRoomToSpectators(room, null) }
    }

    private fun supportsSelectionScreen(player: ServerPlayer): Boolean =
        ServerPlayNetworking.canSend(player, PvpSelectionStatePayload.TYPE)

    private fun remember(first: ServerPlayer, second: ServerPlayer) {
        onlinePlayers[first.uuid] = first
        onlinePlayers[second.uuid] = second
    }

    /** A ranked match's room: its format and its two players, kept after the room itself is gone. */
    private data class RankedMatch(val format: PvpBattleFormat, val leftId: UUID, val rightId: UUID) {
        val playerIds: List<UUID> get() = listOf(leftId, rightId)
    }

    private fun handleRankedIntent(player: ServerPlayer, intent: PvpRankedIntent) {
        when (intent) {
            PvpRankedIntent.Refresh -> sendRankedState(player)
            PvpRankedIntent.Leave -> {
                val left = rankedQueue.leave(player.uuid) != null
                sendRankedState(player)
                if (left) pushRankedQueueSizes(except = player.uuid)
            }
            is PvpRankedIntent.Join -> joinRankedQueue(player, intent.format)
        }
    }

    private fun joinRankedQueue(player: ServerPlayer, format: PvpBattleFormat) {
        val paused = MccContentSwitches.check(player.server, player.uuid, ManagedBattleContentIds.PVP)
        val refusal: Pair<String, List<String>>? = when {
            paused is ContentAccessDecision.Denied -> paused.reasonKey to paused.arguments
            !supportsSelectionScreen(player) -> rankedKey("error.client") to emptyList()
            rooms.roomFor(player.uuid) != null -> rankedKey("error.in_room") to emptyList()
            sessions.challengeFor(player.uuid) != null || BattleRegistry.getBattleByParticipatingPlayerId(player.uuid) != null ->
                rankedKey("error.in_battle") to emptyList()
            else -> when (val team = registerRankedTeam(player, format)) {
                is PvpTeamRegistrationResult.Rejected -> teamInvalidMessage(player.scoreboardName, format, team.issues)
                is PvpTeamRegistrationResult.Accepted -> null
            }
        }
        if (refusal != null) {
            sendRankedState(player, refusal.first, refusal.second)
            return
        }
        // Asking again for the same format keeps the time already waited.
        if (rankedQueue.entryFor(player.uuid)?.format != format) {
            rankedQueue.join(PvpRankedEntry(player.uuid, format, PvpRankedHistory.ladder.rating(player.uuid, format), System.currentTimeMillis()))
        }
        sendRankedState(player)
        pushRankedQueueSizes(except = player.uuid)
        processRankedQueue(player.server)
    }

    private fun registerRankedTeam(player: ServerPlayer, format: PvpBattleFormat): PvpTeamRegistrationResult =
        Cobblemon173PvpTeamFactory.register(player, format, PvpRankedRules.SPECIES_CLAUSE, PvpRankedRules.ITEM_CLAUSE)

    /**
     * Drops whoever can no longer wait (gone, in a room or a match, or PvP switched off for them), then starts a
     * match for every pair the queue offers. Each player and each pair is handled on its own, so one failure never
     * stalls the queue for everyone else.
     */
    private fun processRankedQueue(server: MinecraftServer) {
        var changed = false
        rankedQueue.entries().forEach { entry ->
            runManagedCleanupActionsSafely(
                reportFailure = { failure ->
                    MoreCobblemonContents.LOGGER.error("PvP ranked queue check failed for ${entry.playerId}", failure)
                    // A player whose check keeps failing would fail every second; let them go.
                    if (rankedQueue.leave(entry.playerId) != null) changed = true
                },
                {
                    val player = onlinePlayers[entry.playerId]
                    val paused = MccContentSwitches.check(server, entry.playerId, ManagedBattleContentIds.PVP)
                    val reason: Pair<String, List<String>>? = when {
                        player == null -> "" to emptyList()
                        paused is ContentAccessDecision.Denied -> paused.reasonKey to paused.arguments
                        rooms.roomFor(entry.playerId) != null || sessions.challengeFor(entry.playerId) != null ->
                            rankedKey("dropped.busy") to emptyList()
                        else -> null
                    }
                    if (reason != null && rankedQueue.leave(entry.playerId) != null) {
                        changed = true
                        player?.let { dropFromQueue(it, reason.first, reason.second) }
                    }
                },
            )
        }
        val pairings = rankedQueue.pair(System.currentTimeMillis()) { playerId ->
            BattleRegistry.getBattleByParticipatingPlayerId(playerId) == null
        }
        pairings.forEach { pairing ->
            changed = true
            startRankedMatch(pairing)
        }
        if (changed) pushRankedQueueSizes()
    }

    /** Out of the queue for [messageKey]: said on the ranked tab and in chat, since the hub may well be closed. */
    private fun dropFromQueue(player: ServerPlayer, messageKey: String, messageArgs: List<String>) {
        runManagedCleanupActionsSafely(
            reportFailure = { failure -> MoreCobblemonContents.LOGGER.error("PvP ranked queue notice failed for ${player.uuid}", failure) },
            { sendRankedState(player, messageKey, messageArgs) },
            { player.sendSystemMessage(Component.translatable(rankedKey("dropped"), Component.translatable(messageKey, *messageArgs.toTypedArray()))) },
        )
    }

    /**
     * Seats a pairing in a private room nobody else sees and opens the team preview for both, as a room's start
     * does. A party that no longer fits the rules sends its owner out of the queue and the other back into it. Any
     * failure on the way takes the half-made match apart, so nobody is left in a preview they never saw.
     */
    private fun startRankedMatch(pairing: PvpRankedPairing) {
        val format = pairing.first.format
        val left = onlinePlayers[pairing.first.playerId]
        val right = onlinePlayers[pairing.second.playerId]
        if (left == null || right == null) {
            if (left != null) rankedQueue.join(pairing.first)
            if (right != null) rankedQueue.join(pairing.second)
            return
        }
        var roomId: UUID? = null
        try {
            val leftTeam = registerRankedTeam(left, format)
            val rightTeam = registerRankedTeam(right, format)
            if (leftTeam !is PvpTeamRegistrationResult.Accepted || rightTeam !is PvpTeamRegistrationResult.Accepted) {
                listOf(Triple(left, leftTeam, pairing.first), Triple(right, rightTeam, pairing.second)).forEach { (player, team, entry) ->
                    if (team is PvpTeamRegistrationResult.Rejected) {
                        val (key, args) = teamInvalidMessage(player.scoreboardName, format, team.issues)
                        dropFromQueue(player, key, args)
                    } else {
                        rankedQueue.join(entry)
                        sendRankedState(player)
                    }
                }
                return
            }
            val created = rooms.create(left.uuid, PvpRankedRules.settings(format)).room
            // A host already in a room gets that room back; it is theirs, not a ranked one, so leave it alone.
            if (created.memberIds != setOf(left.uuid)) {
                rankedQueue.join(pairing.first)
                rankedQueue.join(pairing.second)
                return
            }
            roomId = created.roomId
            val match = RankedMatch(format, left.uuid, right.uuid)
            rankedRooms[created.roomId] = match
            rankedMatches[created.roomId] = match
            val seated = rooms.claimSeat(created.roomId, left.uuid, PvpRoomSide.LEFT) is PvpRoomMutation.Applied &&
                rooms.invite(created.roomId, left.uuid, right.uuid) is PvpRoomMutation.Applied &&
                rooms.join(created.roomId, right.uuid) is PvpRoomMutation.Applied &&
                rooms.claimSeat(created.roomId, right.uuid, PvpRoomSide.RIGHT) is PvpRoomMutation.Applied
            val request = PvpChallengeRequest(created.roomId, left.uuid, right.uuid, format, PvpRankedRules.ENABLED_MECHANICS)
            if (!seated || !sessions.prepareRoomMatch(request, leftTeam.team, rightTeam.team)) {
                abandonRankedMatch(created.roomId, pairing)
                return
            }
            if (rooms.startPreview(created.roomId, left.uuid) !is PvpRoomMutation.Applied) {
                sessions.cancel(created.roomId, left.uuid)
                abandonRankedMatch(created.roomId, pairing)
                return
            }
            protectProvisionalPvpStateNotification(
                rollback = { cancelRankedMatch(request) },
            ) {
                sendState(left, null)
                sendState(right, null)
            }
            val ladder = PvpRankedHistory.ladder
            listOf(left to right, right to left).forEach { (player, opponent) ->
                runManagedCleanupActionsSafely(
                    reportFailure = { failure -> MoreCobblemonContents.LOGGER.error("PvP ranked match notice failed for ${player.uuid}", failure) },
                    {
                        player.sendSystemMessage(Component.translatable(rankedKey("matched"),
                            Component.translatable("screen.${MoreCobblemonContents.MOD_ID}.pvp.format.${format.recordId}"),
                            opponent.displayName ?: Component.literal(opponent.scoreboardName), ladder.standing(opponent.uuid, format).rating))
                    },
                    { sendRankedState(player) },
                )
            }
        } catch (failure: RuntimeException) {
            failRankedMatch(pairing, roomId, failure)
        } catch (failure: LinkageError) {
            failRankedMatch(pairing, roomId, failure)
        }
    }

    /** A match that broke while being set up: taken apart without a result, both players told and out of the queue. */
    private fun failRankedMatch(pairing: PvpRankedPairing, roomId: UUID?, failure: Throwable) {
        MoreCobblemonContents.LOGGER.error(
            "PvP ranked match could not start for ${pairing.first.playerId} and ${pairing.second.playerId}", failure,
        )
        runManagedCleanupActionsSafely(
            reportFailure = { cleanupFailure -> MoreCobblemonContents.LOGGER.error("PvP ranked match cleanup failed", cleanupFailure) },
            {
                val challenge = roomId?.let(sessions::challenge)
                if (challenge != null) cancelRankedMatch(challenge.request) else roomId?.let(::finishRankedRoom)
            },
            { rankedQueue.leave(pairing.first.playerId) },
            { rankedQueue.leave(pairing.second.playerId) },
            {
                listOf(pairing.first.playerId, pairing.second.playerId).forEach { playerId ->
                    onlinePlayers[playerId]?.let { dropFromQueue(it, rankedKey("error.match_failed"), emptyList()) }
                }
            },
        )
    }

    /** Calls off a ranked match before its battle, with no result for anyone. */
    private fun cancelRankedMatch(request: PvpChallengeRequest) {
        runManagedCleanupActions(
            { sessions.cancel(request.challengeId, request.challengerId) },
            { notifyClosed(request, "screen.${MoreCobblemonContents.MOD_ID}.pvp.closed.cancelled") },
            { finishRankedRoom(request.challengeId) },
        )
    }

    /** A ranked room that could not be set up: gone again, and both players back in the queue as they were. */
    private fun abandonRankedMatch(roomId: UUID, pairing: PvpRankedPairing) {
        rooms.close(roomId)
        rankedRooms.remove(roomId)
        rankedMatches.remove(roomId)
        MoreCobblemonContents.LOGGER.warn("PvP ranked room {} could not be set up; both players wait again", roomId)
        rankedQueue.join(pairing.first)
        rankedQueue.join(pairing.second)
    }

    private fun markRankedLaunched(matchId: UUID) {
        if (matchId in rankedMatches) rankedLaunched += matchId
    }

    /**
     * A ranked player who leaves the team preview, by cancelling or by logging off, loses: the other player gets the
     * win and both ratings move as after a battle. A preview left because the battle would not start is the server's
     * fault and costs nobody anything.
     */
    private fun forfeitRankedSelection(server: MinecraftServer, matchId: UUID, loserId: UUID) {
        val match = rankedMatches[matchId] ?: return
        if (matchId in rankedLaunched || matchId in retryableMatches) return
        val winnerId = when (loserId) {
            match.leftId -> match.rightId
            match.rightId -> match.leftId
            else -> return
        }
        runManagedCleanupActionsSafely(
            reportFailure = { failure -> MoreCobblemonContents.LOGGER.error("PvP ranked forfeit failed for match $matchId", failure) },
            {
                val result = PvpRankedHistory.settle(matchId, match.format, winnerId, MccAdminArguments.name(server, winnerId),
                    loserId, MccAdminArguments.name(server, loserId))
                if (result != null) {
                    PvpMatchHistory.record(server, matchId, winnerId, loserId, match.format.recordId)
                    fun name(id: UUID): Component = server.playerList.getPlayer(id)?.displayName
                        ?: Component.literal(MccAdminArguments.name(server, id))
                    server.playerList.broadcastSystemMessage(Component.translatable(
                        "chat.${MoreCobblemonContents.MOD_ID}.pvp.ranked_forfeit", name(winnerId), name(loserId),
                        Component.translatable("screen.${MoreCobblemonContents.MOD_ID}.pvp.format.${match.format.recordId}"),
                    ), false)
                    tellRatingChange(server, result)
                }
            },
        )
    }

    /**
     * A ranked match is over, played out or called off: the arena goes, the room goes, and both players get the
     * ranked tab back with their new standing. A match whose battle began stays known until its result settles.
     */
    private fun finishRankedRoom(roomId: UUID) {
        val match = rankedRooms[roomId] ?: return
        runManagedCleanupActionsSafely(
            reportFailure = { failure ->
                MoreCobblemonContents.LOGGER.error("PvP ranked room finish cleanup failed for room $roomId", failure)
            },
            { lounge.finish(roomId) },
            { rooms.close(roomId) },
            { rankedRooms.remove(roomId) },
            { if (roomId !in rankedLaunched) rankedMatches.remove(roomId) },
            { notifyOnlinePlayers(match.playerIds, "ranked finish") { player -> sendRankedState(player, reopen = true) } },
        )
    }

    /** Each player's rating before and after a ranked game, said to them alone. */
    private fun tellRatingChange(server: MinecraftServer, result: PvpRankedResult) {
        listOf(result.winnerBefore to result.winnerAfter, result.loserBefore to result.loserAfter).forEach { (before, after) ->
            val from = before.rating.roundToInt()
            val to = after.rating.roundToInt()
            val delta = to - from
            server.playerList.getPlayer(after.playerId)?.sendSystemMessage(Component.translatable(rankedKey("rating_change"),
                Component.translatable("screen.${MoreCobblemonContents.MOD_ID}.pvp.format.${after.format.recordId}"),
                from, to, if (delta >= 0) "+$delta" else delta.toString()))
        }
    }

    private fun sendRankedState(
        player: ServerPlayer,
        messageKey: String? = null,
        messageArgs: List<String> = emptyList(),
        reopen: Boolean = false,
    ) {
        if (!ServerPlayNetworking.canSend(player, PvpRankedStatePayload.TYPE)) return
        val ladder = PvpRankedHistory.ladder
        val entry = rankedQueue.entryFor(player.uuid)
        ServerPlayNetworking.send(player, PvpRankedStatePayload(
            standings = PvpBattleFormat.entries.map { ladder.standing(player.uuid, it) },
            queuedFormat = entry?.format,
            queuedForMillis = entry?.let { System.currentTimeMillis() - it.joinedAtMillis } ?: 0,
            queueSizes = PvpBattleFormat.entries.associateWith(rankedQueue::size),
            leaders = PvpBattleFormat.entries.associateWith { format ->
                ladder.top(format, PVP_RANKED_MAX_LEADERS).map { record ->
                    PvpRankedLeader(ladder.standing(record.playerId, format).rank, record.name,
                        record.rating.roundToInt(), record.wins, record.losses)
                }
            },
            messageKey = messageKey?.takeIf { it.isNotEmpty() },
            messageArgs = messageArgs.take(4),
            reopen = reopen,
        ))
    }

    /** Everyone online gets the queue counts again; the ranked tab shows how many wait in each format. */
    private fun pushRankedQueueSizes(except: UUID? = null) {
        notifyOnlinePlayers(onlinePlayers.keys.filter { it != except }, "ranked queue update") { player -> sendRankedState(player) }
    }

    private fun rejectFailedRankedRequest(player: ServerPlayer, failure: Throwable) {
        runManagedCleanupActionsSafely(
            reportFailure = {},
            { MoreCobblemonContents.LOGGER.error("PvP ranked request failed for ${player.uuid}", failure) },
            { sendRankedState(player, "screen.${MoreCobblemonContents.MOD_ID}.pvp.error.internal_failure") },
        )
    }

    private fun rankedKey(path: String) = "screen.${MoreCobblemonContents.MOD_ID}.pvp.ranked.$path"

    private fun PvpChallengeMutationResult.toCommandOutcome(): PvpCommandOutcome = when (this) {
        is PvpChallengeMutationResult.Applied, is PvpChallengeMutationResult.Unchanged ->
            PvpCommandOutcome(PvpCommandStatus.APPLIED)
        is PvpChallengeMutationResult.Rejected -> error.toCommandOutcome()
    }

    private fun PvpChallengeMutationError.toCommandOutcome(): PvpCommandOutcome = PvpCommandOutcome(
        when (this) {
            PvpChallengeMutationError.PARTICIPANT_BUSY -> PvpCommandStatus.PARTICIPANT_BUSY
            PvpChallengeMutationError.UNKNOWN_CHALLENGE -> PvpCommandStatus.UNKNOWN_CHALLENGE
            PvpChallengeMutationError.NOT_TARGET -> PvpCommandStatus.WRONG_CHALLENGER
            else -> PvpCommandStatus.INVALID_STATE
        },
    )
}

internal fun attemptUntrackedPvpSpectatorRecovery(
    hasPendingReturn: Boolean,
    inLoungeDimension: Boolean,
    restorePending: () -> Boolean,
    rescueStranded: () -> Boolean,
    notifyInactive: () -> Unit,
): Boolean = when {
    hasPendingReturn -> restorePending()
    inLoungeDimension -> rescueStranded()
    else -> {
        notifyInactive()
        true
    }
}

/** How often lobbies' invite lists are checked for players who became busy or free elsewhere: once a second. */
private const val LOBBY_INVITE_REFRESH_TICKS = 20

/**
 * Who a room's host can invite: online players outside the room and not [busy] in another room or match, by name,
 * at most [PVP_ROOM_MAX_INVITE_CANDIDATES] so the room packet stays inside what the client reads.
 */
internal fun pvpInviteCandidates(
    onlinePlayerIds: Iterable<UUID>,
    memberIds: Collection<UUID>,
    memberView: (UUID) -> PvpRoomMemberView,
    busy: (UUID) -> Boolean = { false },
): List<PvpRoomMemberView> = onlinePlayerIds
    .asSequence()
    .filter { it !in memberIds && !busy(it) }
    .map(memberView)
    .sortedBy(PvpRoomMemberView::name)
    .take(PVP_ROOM_MAX_INVITE_CANDIDATES)
    .toList()

/** How often the ranked queue looks for pairs and drops who cannot wait: once a second. */
private const val RANKED_PAIRING_TICKS = 20

/** The room an intent acts on, for the intents that name one. */
private fun PvpRoomIntent.targetRoomId(): UUID? = when (this) {
    is PvpRoomIntent.Refresh, is PvpRoomIntent.Create -> null
    is PvpRoomIntent.Join -> roomId
    is PvpRoomIntent.Leave -> roomId
    is PvpRoomIntent.ClaimSeat -> roomId
    is PvpRoomIntent.Observe -> roomId
    is PvpRoomIntent.UpdateSettings -> roomId
    is PvpRoomIntent.Invite -> roomId
    is PvpRoomIntent.DeclineInvite -> roomId
    is PvpRoomIntent.TransferHost -> roomId
    is PvpRoomIntent.Start -> roomId
}

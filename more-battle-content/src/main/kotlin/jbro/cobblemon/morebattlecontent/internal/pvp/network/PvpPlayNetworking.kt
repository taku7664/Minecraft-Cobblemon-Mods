package jbro.cobblemon.morebattlecontent.internal.pvp.network

import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import java.util.UUID
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.command.PvpCommandBackend
import jbro.cobblemon.morebattlecontent.internal.command.PvpCommandOutcome
import jbro.cobblemon.morebattlecontent.internal.command.PvpCommandStatus
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.Cobblemon173PvpBattleRuntime
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.Cobblemon173ManagedBattleTermination
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.Cobblemon173PvpLoungeGateway
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.Cobblemon173PvpRegisteredTeamSnapshotStore
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.Cobblemon173PvpTeamFactory
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.Cobblemon173PvpTurnHooks
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.reportManagedCleanupFailureSafely
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.runManagedCleanupActions
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.runManagedCleanupActionsSafely
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.runManagedCleanupForEachSafely
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleFormat
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpArenaPool
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleCompletionSink
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleLauncher
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleRecordService
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleLaunchResult
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpChallengeMutationError
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpChallengeMutationResult
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpChallengePhase
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpChallengeRequest
import jbro.cobblemon.morebattlecontent.internal.pvp.PendingPvpCompletion
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpCompletionRetryQueue
import jbro.cobblemon.morebattlecontent.internal.pvp.attemptPvpCompletionSettlement
import jbro.cobblemon.morebattlecontent.internal.pvp.PVP_COMPLETION_RETRY_MILLIS
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpMatchPhase
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpLoungeCoordinator
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpLoungeRescuePolicy
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomError
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomBattlePlacement
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomMutation
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomService
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomView
import jbro.cobblemon.morebattlecontent.internal.pvp.leaveRequestError
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpSelectionMutation
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpSessionService
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpTeamRegistrationMutation
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpTeamRegistrationResult
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpTurnCapture
import jbro.cobblemon.morebattlecontent.internal.pvp.ui.PvpSelectionIntent
import jbro.cobblemon.morebattlecontent.internal.pvp.ui.PvpSelectionOpponentSlot
import jbro.cobblemon.morebattlecontent.internal.pvp.ui.PvpSelectionPartySlot
import jbro.cobblemon.morebattlecontent.internal.pvp.ui.PvpSelectionSpectator
import jbro.cobblemon.morebattlecontent.internal.pvp.ui.PvpSelectionViewState
import jbro.cobblemon.morebattlecontent.internal.hub.BattleHubNetworking
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordService
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

internal object PvpPlayNetworking : PvpCommandBackend {
    private val onlinePlayers = HashMap<UUID, ServerPlayer>()
    private val retryableMatches = HashSet<UUID>()
    private val pendingCompletions = PvpCompletionRetryQueue()
    private val rooms = PvpRoomService()
    private var currentServer: MinecraftServer? = null
    private val loungeGateway by lazy { Cobblemon173PvpLoungeGateway({ currentServer }, onlinePlayers::get) }
    private val arenas = PvpArenaPool()
    private val lounge by lazy { PvpLoungeCoordinator(arenas, loungeGateway) }
    private val snapshots = Cobblemon173PvpRegisteredTeamSnapshotStore(onlinePlayers::get)
    private val runtime: Cobblemon173PvpBattleRuntime by lazy {
        Cobblemon173PvpBattleRuntime(
            playerResolver = onlinePlayers::get,
            completion = { server, matchId, winnerId, loserId, battleId ->
                val pending = PendingPvpCompletion(matchId, battleId, winnerId, loserId)
                pendingCompletions.submit(pending) { completion -> settleCompletion(server, completion) }
                retryableMatches.remove(matchId)
                finishRoom(matchId)
            },
            cancellation = { _, matchId, battleId ->
                try {
                    sessions.cancelBattle(matchId, battleId)
                } finally {
                    retryableMatches.remove(matchId)
                    finishRoom(matchId)
                }
            },
        )
    }
    private val launcher: PvpBattleLauncher<BattlePokemon> by lazy {
        PvpBattleLauncher(
            materialize = snapshots,
            runtime = runtime,
            placement = PvpRoomBattlePlacement(rooms, lounge),
            abortBattle = Cobblemon173ManagedBattleTermination::end,
            diagnostics = { reason -> MoreBattleContent.LOGGER.error("PvP launch failed: {}", reason) },
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
                        messageKey = "screen.${MoreBattleContent.MOD_ID}.pvp.error.invalid_state",
                    )
                    PvpSpectatorExitResult.INTERNAL_FAILURE -> PvpLoungeExitResultPayload(
                        accepted = false,
                        messageKey = "screen.${MoreBattleContent.MOD_ID}.pvp.error.internal_failure",
                    )
                }
            } catch (exception: RuntimeException) {
                reportPvpFailureSafely("PvP spectator exit failed for ${player.uuid}", exception)
                PvpLoungeExitResultPayload(
                    accepted = false,
                    messageKey = "screen.${MoreBattleContent.MOD_ID}.pvp.error.internal_failure",
                )
            } catch (error: LinkageError) {
                reportPvpFailureSafely("PvP spectator exit failed for ${player.uuid}", error)
                PvpLoungeExitResultPayload(
                    accepted = false,
                    messageKey = "screen.${MoreBattleContent.MOD_ID}.pvp.error.internal_failure",
                )
            }
            runManagedCleanupActionsSafely(
                reportFailure = { failure ->
                    MoreBattleContent.LOGGER.error("PvP spectator exit response failed for ${player.uuid}", failure)
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
                    MoreBattleContent.LOGGER.error("PvP join recovery failed for ${player.uuid}", failure)
                },
                { onlinePlayers[player.uuid] = player },
                { rescueStrandedLoungePlayer(player) },
            )
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            val playerId = handler.player.uuid
            var room: PvpRoomView? = null
            var remaining: PvpRoomView? = null
            runManagedCleanupActionsSafely(
                reportFailure = { failure ->
                    MoreBattleContent.LOGGER.error("PvP disconnect cleanup failed for $playerId", failure)
                },
                { onlinePlayers.remove(playerId) },
                { room = rooms.roomFor(playerId) },
                {
                    val challenge = sessions.challengeFor(playerId)
                    if (challenge != null &&
                        challenge.phase in setOf(PvpChallengePhase.PENDING, PvpChallengePhase.TEAM_REGISTRATION)
                    ) {
                        val matchId = challenge.request.challengeId
                        runManagedCleanupActions(
                            { sessions.cancel(matchId, playerId) },
                            { retryableMatches.remove(matchId) },
                            {
                                notifyClosed(
                                    challenge.request,
                                    "screen.${MoreBattleContent.MOD_ID}.pvp.closed.disconnected",
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
                    if (disconnectedRoom?.phase == jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomPhase.ACTIVE &&
                        playerId in disconnectedRoom.spectatorIds
                    ) {
                        lounge.disconnectSpectator(disconnectedRoom.roomId, playerId)
                    }
                },
                { remaining = rooms.disconnect(playerId) },
                {
                    remaining?.let { updatedRoom ->
                        pushRoomToMembers(updatedRoom, null)
                        if (updatedRoom.phase == jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomPhase.TEAM_PREVIEW) {
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
                    MoreBattleContent.LOGGER.error("PvP server shutdown cleanup failed", failure)
                },
                { processPendingCompletions(server, force = true) },
                {
                    sessions.activeBattles().values.toSet().forEach { battleId ->
                        runManagedCleanupActionsSafely(
                            reportFailure = { failure ->
                                MoreBattleContent.LOGGER.error(
                                    "PvP battle $battleId could not be terminated during server shutdown",
                                    failure,
                                )
                            },
                            { Cobblemon173ManagedBattleTermination.end(battleId) },
                        )
                    }
                },
                lounge::shutdown,
                sessions::clear,
                rooms::clear,
                retryableMatches::clear,
                {
                    if (pendingCompletions.size() > 0) {
                        MoreBattleContent.LOGGER.error(
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
                    MoreBattleContent.LOGGER.error("PvP server tick cleanup failed", failure)
                },
                { currentServer = server },
                ::processEntryTimeouts,
                turnHooks::processTimeouts,
                { processPendingCompletions(server) },
                { lounge.restoreAvailable(onlinePlayers::containsKey) },
                loungeGateway::enforceSpectatorAnchors,
            )
        }
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
            Component.translatable("screen.${MoreBattleContent.MOD_ID}.pvp.lounge.rescued"),
        )
        MoreBattleContent.LOGGER.info("Moved {} out of the battle lounge; no return point was recorded", player.uuid)
        return true
    }

    override fun open(player: ServerPlayer): PvpCommandOutcome {
        onlinePlayers[player.uuid] = player
        if (!ServerPlayNetworking.canSend(player, PvpRoomListStatePayload.TYPE)) {
            return PvpCommandOutcome(PvpCommandStatus.CLIENT_UNSUPPORTED)
        }
        BattleHubNetworking.sendHeader(player)
        sendRoomList(player, null)
        return PvpCommandOutcome(PvpCommandStatus.APPLIED)
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
        return when (val result = sessions.invite(request)) {
            is PvpChallengeMutationResult.Applied -> {
                opponent.sendSystemMessage(
                    Component.translatable(
                        "command.${MoreBattleContent.MOD_ID}.pvp.invited",
                        challenger.scoreboardName,
                        Component.translatable("screen.${MoreBattleContent.MOD_ID}.pvp.format.${format.recordId}"),
                        challenger.scoreboardName,
                        challenger.scoreboardName,
                    ),
                )
                PvpCommandOutcome(PvpCommandStatus.APPLIED)
            }
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
        val accepted = sessions.accept(challenge.request.challengeId, opponent.uuid)
        if (accepted !is PvpChallengeMutationResult.Applied) return accepted.toCommandOutcome()

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
        val challengerStored = sessions.registerTeam(challenge.request.challengeId, challenger.uuid, challengerTeam.team)
        val opponentStored = sessions.registerTeam(challenge.request.challengeId, opponent.uuid, opponentTeam.team)
        if (challengerStored != PvpTeamRegistrationMutation.STORED || opponentStored != PvpTeamRegistrationMutation.STORED) {
            sessions.cancel(challenge.request.challengeId, opponent.uuid)
            return PvpCommandOutcome(PvpCommandStatus.INVALID_STATE)
        }
        sendState(challenger, null)
        sendState(opponent, null)
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
            retryableMatches.remove(challenge.request.challengeId)
            notifyClosed(challenge.request, "screen.${MoreBattleContent.MOD_ID}.pvp.closed.cancelled")
            finishRoom(challenge.request.challengeId)
        }
        return result.toCommandOutcome()
    }

    private fun handleRoomIntent(player: ServerPlayer, intent: PvpRoomIntent) {
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
                if (current.phase == jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomPhase.ACTIVE &&
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
                if (remaining != null) pushRoomToMembers(remaining, null)
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
                val result = rooms.invite(intent.roomId, player.uuid, intent.targetId)
                if (result is PvpRoomMutation.Applied) {
                    onlinePlayers[intent.targetId]?.let { target ->
                        if (ServerPlayNetworking.canSend(target, PvpRoomInvitePayload.TYPE)) {
                            ServerPlayNetworking.send(
                                target,
                                PvpRoomInvitePayload(intent.roomId, player.scoreboardName),
                            )
                        } else {
                            target.sendSystemMessage(
                                Component.translatable(
                                    "screen.${MoreBattleContent.MOD_ID}.pvp.room.invited.fallback",
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
                    is PvpRoomMutation.Applied -> player.sendSystemMessage(
                        Component.translatable("screen.${MoreBattleContent.MOD_ID}.pvp.room.invite.declined"),
                    )
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
        if (!wasMember && joined.room.phase == jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomPhase.ACTIVE) {
            val targetId = joined.room.leftPlayerId
            if (targetId == null || !lounge.addSpectator(joined.room.roomId, player.uuid, targetId)) {
                val battleId = sessions.battleIdFor(joined.room.roomId)
                if (battleId == null || BattleRegistry.getBattle(battleId) == null) {
                    runManagedCleanupActionsSafely(
                        reportFailure = { failure ->
                            MoreBattleContent.LOGGER.error(
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
        if (joined.room.phase == jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomPhase.TEAM_PREVIEW &&
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
        val leftTeam = Cobblemon173PvpTeamFactory.register(left, room.settings.format)
        val rightTeam = Cobblemon173PvpTeamFactory.register(right, room.settings.format)
        if (leftTeam !is PvpTeamRegistrationResult.Accepted || rightTeam !is PvpTeamRegistrationResult.Accepted) {
            ServerPlayNetworking.send(
                player,
                PvpRoomRejectedPayload(
                    intent.requestId,
                    "screen.${MoreBattleContent.MOD_ID}.pvp.room.error.team_invalid",
                ),
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
        if (sessions.invite(request) !is PvpChallengeMutationResult.Applied ||
            sessions.accept(room.roomId, rightId) !is PvpChallengeMutationResult.Applied
        ) {
            rejectRoom(player, intent.requestId, PvpRoomError.INVALID_PHASE)
            return
        }
        val storedLeft = sessions.registerTeam(room.roomId, leftId, leftTeam.team)
        val storedRight = sessions.registerTeam(room.roomId, rightId, rightTeam.team)
        if (storedLeft != PvpTeamRegistrationMutation.STORED || storedRight != PvpTeamRegistrationMutation.STORED) {
            sessions.cancel(room.roomId, leftId)
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
        sendState(left, null)
        sendState(right, null)
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
        val summaries = rooms.visibleRoomsFor(player.uuid).map(::summaryView)
        ServerPlayNetworking.send(player, PvpRoomListStatePayload(requestId, summaries))
    }

    private fun sendRoomListSafely(player: ServerPlayer, requestId: UUID?, operation: String) {
        runManagedCleanupActionsSafely(
            reportFailure = { failure ->
                MoreBattleContent.LOGGER.error("PvP $operation failed for ${player.uuid}", failure)
            },
            { sendRoomList(player, requestId) },
        )
    }

    private fun sendRoom(player: ServerPlayer, room: PvpRoomView, requestId: UUID?, reopen: Boolean = false) {
        ServerPlayNetworking.send(player, PvpRoomStatePayload(requestId, clientView(room), reopen))
    }

    private fun pushRoomToMembers(room: PvpRoomView, requestId: UUID?, reopen: Boolean = false) {
        notifyOnlinePlayers(room.memberIds, "room update") { player -> sendRoom(player, room, requestId, reopen) }
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
                MoreBattleContent.LOGGER.error("PvP $notification failed for $playerId", failure)
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
            recoverUntrackedSpectator(player)
            return PvpSpectatorExitResult.ACCEPTED
        }
        if (player.uuid !in room.spectatorIds) return PvpSpectatorExitResult.INVALID_STATE

        var remaining: PvpRoomView? = null
        var roomLeaveCompleted = false
        var stateCleanupFailed = false
        runManagedCleanupActionsSafely(
            reportFailure = { failure ->
                stateCleanupFailed = true
                MoreBattleContent.LOGGER.error("PvP spectator exit cleanup failed for ${player.uuid}", failure)
            },
            {
                if (room.phase == jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomPhase.ACTIVE &&
                    !lounge.removeSpectator(room.roomId, player.uuid)
                ) {
                    recoverUntrackedSpectator(player)
                }
            },
            {
                remaining = rooms.leave(room.roomId, player.uuid)
                roomLeaveCompleted = true
            },
        )
        runManagedCleanupActionsSafely(
            reportFailure = { failure ->
                MoreBattleContent.LOGGER.error("PvP spectator exit notification failed for ${player.uuid}", failure)
            },
            {
                remaining?.let { updatedRoom ->
                    pushRoomToMembers(updatedRoom, null)
                    if (updatedRoom.phase == jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomPhase.TEAM_PREVIEW) {
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
            MoreBattleContent.LOGGER.error(message, it)
        }
    }

    private fun rejectFailedSelectionRequest(
        player: ServerPlayer,
        intent: PvpSelectionIntent,
        failure: Throwable,
    ) {
        runManagedCleanupActionsSafely(
            reportFailure = {},
            { MoreBattleContent.LOGGER.error("PvP selection request failed for ${player.uuid}", failure) },
            { reject(player, intent, "screen.${MoreBattleContent.MOD_ID}.pvp.error.internal_failure") },
        )
    }

    private fun rejectFailedRoomRequest(player: ServerPlayer, requestId: UUID, failure: Throwable) {
        runManagedCleanupActionsSafely(
            reportFailure = {},
            { MoreBattleContent.LOGGER.error("PvP room request failed for ${player.uuid}", failure) },
            { rejectRoom(player, requestId, PvpRoomError.INVALID_PHASE) },
        )
    }

    private enum class PvpSpectatorExitResult {
        ACCEPTED,
        INVALID_STATE,
        INTERNAL_FAILURE,
    }

    private fun recoverUntrackedSpectator(player: ServerPlayer) {
        if (player.uuid in lounge.pendingReturnPlayerIds()) {
            lounge.restorePending(player.uuid)
            return
        }
        if (rescueStrandedLoungePlayer(player)) return
        if (ServerPlayNetworking.canSend(player, PvpLoungeSpectatorStatePayload.TYPE)) {
            ServerPlayNetworking.send(player, PvpLoungeSpectatorStatePayload(false))
        }
    }

    /**
     * Tears down the arena a match used and returns its room to the lobby, keeping the group together
     * for a rematch. Only a room that no longer exists falls back to the room list.
     */
    private fun finishRoom(roomId: UUID) {
        var room: PvpRoomView? = null
        runManagedCleanupActionsSafely(
            reportFailure = { failure ->
                MoreBattleContent.LOGGER.error("PvP room finish cleanup failed for room $roomId", failure)
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
        inviteCandidates = onlinePlayers.keys
            .asSequence()
            .filter { it !in room.memberIds }
            .map(::memberView)
            .sortedBy(PvpRoomMemberView::name)
            .toList(),
    )

    private fun memberView(playerId: UUID) = PvpRoomMemberView(
        playerId,
        onlinePlayers[playerId]?.scoreboardName ?: playerId.toString(),
    )

    private fun rejectRoom(player: ServerPlayer, requestId: UUID, error: PvpRoomError) {
        ServerPlayNetworking.send(
            player,
            PvpRoomRejectedPayload(
                requestId,
                "screen.${MoreBattleContent.MOD_ID}.pvp.room.error.${error.name.lowercase()}",
            ),
        )
    }

    private fun handleIntent(player: ServerPlayer, intent: PvpSelectionIntent) {
        val view = sessions.viewFor(player.uuid)
        if (view == null || view.matchId != intent.matchId) {
            reject(player, intent, "screen.${MoreBattleContent.MOD_ID}.pvp.error.session_not_found")
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
                    retryableMatches.remove(intent.matchId)
                    notifyClosed(challenge.request, "screen.${MoreBattleContent.MOD_ID}.pvp.closed.cancelled")
                    finishRoom(intent.matchId)
                } else {
                    reject(player, intent, "screen.${MoreBattleContent.MOD_ID}.pvp.error.invalid_state")
                }
            }
            is PvpSelectionIntent.Unready -> {
                if (sessions.unready(intent.matchId, player.uuid)) {
                    sendState(player, intent.requestId)
                } else {
                    reject(player, intent, "screen.${MoreBattleContent.MOD_ID}.pvp.error.invalid_state")
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
                val challenge = sessions.challengeFor(player.uuid) ?: return
                retryableMatches.remove(intent.matchId)
                pushActiveRoomToSpectators(intent.matchId)
                notifyClosed(challenge.request, "screen.${MoreBattleContent.MOD_ID}.pvp.closed.battle_started")
            }
            PvpSelectionMutation.BATTLE_UNAVAILABLE -> {
                retryableMatches += intent.matchId
                sessions.challenge(intent.matchId)?.request?.let(::sendStateToBoth)
            }
            PvpSelectionMutation.ENTRY_EXPIRED -> processEntryTimeouts()
            PvpSelectionMutation.INVALID_SELECTION ->
                reject(player, intent, "screen.${MoreBattleContent.MOD_ID}.pvp.error.invalid_selection")
            PvpSelectionMutation.UNKNOWN_MATCH ->
                reject(player, intent, "screen.${MoreBattleContent.MOD_ID}.pvp.error.session_not_found")
            else -> reject(player, intent, "screen.${MoreBattleContent.MOD_ID}.pvp.error.invalid_state")
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

    private fun notifyClosed(request: PvpChallengeRequest, messageKey: String) {
        notifyOnlinePlayers(listOf(request.challengerId, request.opponentId), "selection close") { player ->
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
                MoreBattleContent.LOGGER.error("PvP entry timeout cleanup failed for ${resolution.matchId}", failure)
            },
        ) { resolution ->
            val request = sessions.challenge(resolution.matchId)?.request ?: return@runManagedCleanupForEachSafely
            when (resolution.launchResult) {
                PvpSelectionMutation.BATTLE_STARTED -> {
                    retryableMatches.remove(resolution.matchId)
                    pushActiveRoomToSpectators(resolution.matchId)
                    notifyClosed(request, "screen.${MoreBattleContent.MOD_ID}.pvp.closed.battle_started")
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
                val accepted = sessions.completeBattle(
                    pending.matchId,
                    pending.battleId,
                    pending.winnerId,
                    pending.loserId,
                    PvpBattleCompletionSink { winnerId, loserId, format ->
                        PvpBattleRecordService { completions ->
                            BattleRecordService.recordCompletedBattles(server, completions)
                        }.recordResult(winnerId, loserId, format)
                    },
                )
                if (!accepted) {
                    MoreBattleContent.LOGGER.warn(
                        "Dropping stale PvP completion retry for match {} and battle {}",
                        pending.matchId,
                        pending.battleId,
                    )
                }
                true
            },
            reportFailure = { failure ->
                MoreBattleContent.LOGGER.error(
                    "PvP record settlement failed for match {} and battle {}; retrying in {} ms",
                    pending.matchId,
                    pending.battleId,
                    PVP_COMPLETION_RETRY_MILLIS,
                    failure,
                )
            },
        )

    private fun pushActiveRoomToSpectators(matchId: UUID) {
        rooms.get(matchId)
            ?.takeIf { room -> room.phase == jbro.cobblemon.morebattlecontent.internal.pvp.PvpRoomPhase.ACTIVE }
            ?.let { room -> pushRoomToSpectators(room, null) }
    }

    private fun supportsSelectionScreen(player: ServerPlayer): Boolean =
        ServerPlayNetworking.canSend(player, PvpSelectionStatePayload.TYPE)

    private fun remember(first: ServerPlayer, second: ServerPlayer) {
        onlinePlayers[first.uuid] = first
        onlinePlayers[second.uuid] = second
    }

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

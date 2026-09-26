package jbro.cobblemon.morebattlecontent.internal.tower.network

import jbro.cobblemon.morebattlecontent.api.access.BattleContentAccess
import jbro.cobblemon.morebattlecontent.api.access.ContentAccessAction
import jbro.cobblemon.morebattlecontent.api.access.ContentAccessDecision
import jbro.cobblemon.morebattlecontent.api.presentation.ManagedBattleContentIds

import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.application.BattleContentId
import jbro.cobblemon.morebattlecontent.internal.battle.BattleCompletionRetryQueue
import jbro.cobblemon.morebattlecontent.internal.battle.attemptBattleCompletionSettlement
import jbro.cobblemon.morebattlecontent.internal.battle.finalizeCompletionOwner
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointRewardSettlementService
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointRewardSettlement
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointService
import jbro.cobblemon.morebattlecontent.internal.bp.requireAcceptedReward
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.Cobblemon173TowerPlayOpenRequestFactory
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.Cobblemon173TowerRegisteredTeamSnapshotStore
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.Cobblemon173BattleForfeit
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.Cobblemon173OpponentPokemonPropertiesFactory
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.Cobblemon173TowerPveBattleRuntime
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.Cobblemon173ManagedBattleTermination
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.reportManagedCleanupFailureSafely
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.runManagedCleanupActions
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.runManagedCleanupActionsSafely
import jbro.cobblemon.morebattlecontent.internal.compat.fabric.TowerOpponentCatalogResources
import jbro.cobblemon.morebattlecontent.internal.compat.fabric.dispatchToServerThread
import jbro.cobblemon.morebattlecontent.internal.command.BattleProgressSetResult
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordCategory
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordKey
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordService
import jbro.cobblemon.morebattlecontent.internal.hub.BattleHubNetworking
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleRecordService
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleOutcome
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleFormat
import jbro.cobblemon.morebattlecontent.internal.tower.TowerProgressRecordCodec
import jbro.cobblemon.morebattlecontent.internal.tower.TowerRecordContract
import jbro.cobblemon.morebattlecontent.internal.tower.TowerPveBattleLauncher
import jbro.cobblemon.morebattlecontent.internal.tower.application.BattleTowerApplicationBackend
import jbro.cobblemon.morebattlecontent.internal.tower.opponent.TowerOpponentRandom
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayBattleCompletionResult
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayMutationResult
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayEntryContext
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayBattleCompletionSink
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlaySessionService
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayViewState
import jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerSessionAbandonResult
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import kotlin.random.Random

internal object TowerPlayNetworking : BattleTowerApplicationBackend {
    private const val COMPLETION_RETRY_MILLIS = 5_000L
    private val onlinePlayers = HashMap<java.util.UUID, ServerPlayer>()
    private val pendingCompletions = BattleCompletionRetryQueue<java.util.UUID, PendingTowerCompletion>(
        keyOf = PendingTowerCompletion::battleId,
        retryMillis = COMPLETION_RETRY_MILLIS,
    )
    private val registeredTeamSnapshots = Cobblemon173TowerRegisteredTeamSnapshotStore(onlinePlayers::get)
    private val runtime: Cobblemon173TowerPveBattleRuntime by lazy {
        Cobblemon173TowerPveBattleRuntime(
            playerResolver = onlinePlayers::get,
            sessionCompletion = { server, playerId, battleId, outcome ->
                submitCompletion(server, PendingTowerCompletion(playerId, battleId, outcome))
            },
            sessionCancellation = { server, playerId, battleId ->
                submitCompletion(server, PendingTowerCompletion(playerId, battleId, null))
            },
        )
    }
    private val launcher: TowerPveBattleLauncher<BattlePokemon, BattlePokemon> by lazy {
        TowerPveBattleLauncher(
            registeredTeamMaterializer = registeredTeamSnapshots::materialize,
            catalogSource = TowerOpponentCatalogResources.store::snapshot,
            opponentMemberFactory = Cobblemon173OpponentPokemonPropertiesFactory::toBattlePokemon,
            runtime = runtime,
            random = object : TowerOpponentRandom {
                override fun nextLong(bound: Long): Long = Random.Default.nextLong(bound)
                override fun nextInt(bound: Int): Int = Random.Default.nextInt(bound)
            },
            diagnostics = { reason -> MoreBattleContent.LOGGER.error("Battle Tower launch failed: {}", reason) },
        )
    }
    private val sessions: TowerPlaySessionService by lazy {
        TowerPlaySessionService(
            battleLauncher = launcher,
            registeredTeamSnapshots = registeredTeamSnapshots,
        )
    }

    fun registerServer() {
        sessions
        PayloadTypeRegistry.playS2C().register(TowerPlayStatePayload.TYPE, TowerPlayStatePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(TowerPlayRejectedPayload.TYPE, TowerPlayRejectedPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(TowerPlayIntentPayload.TYPE, TowerPlayIntentPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(TowerPlayIntentPayload.TYPE) { payload, context ->
            val player = context.player()
            onlinePlayers[player.uuid] = player
            val access = BattleContentAccess.check(player, ManagedBattleContentIds.BATTLE_TOWER, ContentAccessAction.MUTATE)
            if (access is ContentAccessDecision.Denied && payload.intent !is jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayIntent.Abandon) {
                ServerPlayNetworking.send(player, TowerPlayRejectedPayload(TowerPlayMutationResult.Rejected(
                    payload.intent.requestId, payload.intent.expectedRevision, access.reasonKey,
                )))
                return@registerGlobalReceiver
            }
            val result = try {
                val currentParty = if (payload.intent is jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayIntent.LockTeam) {
                    Cobblemon173TowerPlayOpenRequestFactory.readParty(player)
                } else {
                    null
                }
                sessions.mutate(player.uuid, payload.intent, currentParty)
            } catch (exception: RuntimeException) {
                rejectFailedMutation(player, payload.intent.requestId, payload.intent.expectedRevision, exception)
                return@registerGlobalReceiver
            } catch (error: LinkageError) {
                rejectFailedMutation(player, payload.intent.requestId, payload.intent.expectedRevision, error)
                return@registerGlobalReceiver
            }
            try {
                when (result) {
                    is TowerPlayMutationResult.Accepted ->
                        ServerPlayNetworking.send(player, TowerPlayStatePayload(result.requestId, result.state))

                    is TowerPlayMutationResult.Rejected ->
                        ServerPlayNetworking.send(player, TowerPlayRejectedPayload(result))
                }
            } catch (failure: RuntimeException) {
                reportMutationResponseFailure(player, failure)
            } catch (failure: LinkageError) {
                reportMutationResponseFailure(player, failure)
            }
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            runManagedCleanupActionsSafely(
                reportFailure = { failure ->
                    MoreBattleContent.LOGGER.error("Battle Tower server shutdown cleanup failed", failure)
                },
                { processPendingCompletions(server, force = true) },
                {
                    sessions.activeBattleIds().forEach { battleId ->
                        runManagedCleanupActionsSafely(
                            reportFailure = { failure ->
                                MoreBattleContent.LOGGER.error(
                                    "Battle Tower battle $battleId could not be terminated during server shutdown",
                                    failure,
                                )
                            },
                            { Cobblemon173ManagedBattleTermination.end(battleId) },
                        )
                    }
                },
                { processPendingCompletions(server, force = true) },
                {
                    if (pendingCompletions.size() > 0) {
                        MoreBattleContent.LOGGER.error(
                            "Discarding {} Battle Tower completion retries because the server is stopping and record storage is still unavailable",
                            pendingCompletions.size(),
                        )
                    }
                },
                pendingCompletions::clear,
                sessions::clear,
                launcher::clear,
                onlinePlayers::clear,
            )
        }
        ServerTickEvents.END_SERVER_TICK.register { server -> processPendingCompletions(server) }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            onlinePlayers[handler.player.uuid] = handler.player
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            val playerId = handler.player.uuid
            dispatchToServerThread(server.isSameThread, { action -> server.execute(action) }) {
                processPendingCompletions(server, force = true)
                runManagedCleanupActionsSafely(
                    reportFailure = { failure ->
                        MoreBattleContent.LOGGER.error("Battle Tower disconnect cleanup failed for $playerId", failure)
                    },
                    {
                        if (!pendingCompletions.any { it.playerId == playerId }) {
                            val battleId = sessions.activeBattleId(playerId)
                            if (battleId == null) {
                                sessions.disconnect(playerId)
                            } else {
                                sessions.disconnect(
                                    playerId,
                                    completionSink(server, battleId),
                                    Cobblemon173ManagedBattleTermination::end,
                                )
                            }
                        }
                    },
                    {
                        if (!pendingCompletions.any { it.playerId == playerId }) sessions.close(playerId)
                    },
                    {
                        if (!pendingCompletions.any { it.playerId == playerId }) launcher.forget(playerId)
                    },
                    { onlinePlayers.remove(playerId) },
                )
            }
        }
    }

    fun open(
        player: ServerPlayer,
        initialFormat: TowerBattleFormat = TowerBattleFormat.SINGLE,
        entryContext: TowerPlayEntryContext? = null,
    ): Boolean {
        if (!BattleContentAccess.allow(player, ManagedBattleContentIds.BATTLE_TOWER, ContentAccessAction.OPEN)) return false
        if (!ServerPlayNetworking.canSend(player, TowerPlayStatePayload.TYPE)) return false
        return try {
            onlinePlayers[player.uuid] = player
            val request = Cobblemon173TowerPlayOpenRequestFactory.create(player, initialFormat)
            val state = if (entryContext == null) {
                sessions.open(player.uuid, request)
            } else {
                sessions.open(player.uuid, request, entryContext)
            }
            BattleHubNetworking.sendHeader(player)
            ServerPlayNetworking.send(player, TowerPlayStatePayload(null, state))
            true
        } catch (exception: RuntimeException) {
            reportOpenFailure(player, exception)
            false
        } catch (error: LinkageError) {
            reportOpenFailure(player, error)
            false
        }
    }

    private fun rejectFailedMutation(
        player: ServerPlayer,
        requestId: java.util.UUID,
        expectedRevision: Long,
        failure: Throwable,
    ) {
        runManagedCleanupActionsSafely(
            reportFailure = {},
            {
                MoreBattleContent.LOGGER.error("Battle Tower screen mutation failed for ${player.uuid}", failure)
            },
            {
                ServerPlayNetworking.send(
                    player,
                    TowerPlayRejectedPayload(
                        TowerPlayMutationResult.Rejected(
                            requestId,
                            expectedRevision,
                            "screen.${MoreBattleContent.MOD_ID}.tower.error.internal_failure",
                        ),
                    ),
                )
            },
        )
    }

    private fun reportOpenFailure(player: ServerPlayer, failure: Throwable) {
        reportManagedCleanupFailureSafely(failure) {
            MoreBattleContent.LOGGER.error("Battle Tower screen could not be opened for ${player.uuid}", it)
        }
    }

    private fun reportMutationResponseFailure(player: ServerPlayer, failure: Throwable) {
        reportManagedCleanupFailureSafely(failure) {
            MoreBattleContent.LOGGER.error("Battle Tower screen response failed for ${player.uuid}", it)
        }
    }

    override fun current(playerId: java.util.UUID): TowerPlayViewState? = sessions.current(playerId)

    override fun progress(playerId: java.util.UUID) =
        sessions.progress(playerId)
            ?: onlinePlayers[playerId]?.let(Cobblemon173TowerPlayOpenRequestFactory::readProgress)
            ?: emptyMap()

    fun adminSetStreak(
        player: ServerPlayer,
        format: TowerBattleFormat,
        value: Int,
        resetBest: Boolean = false,
    ): BattleProgressSetResult {
        onlinePlayers[player.uuid] = player
        val current = sessions.current(player.uuid)
        if (sessions.activeBattleId(player.uuid) != null && current?.format == format) {
            return BattleProgressSetResult.ActiveBattle
        }
        if (!BattleRecordService.isAvailable(player.server)) {
            return BattleProgressSetResult.StorageUnavailable
        }
        val key = BattleRecordKey(
            player.uuid,
            BattleRecordCategory(TowerRecordContract.CONTENT_ID, format.recordId),
        )
        val before = BattleRecordService.get(player.server, key)
        val stats = if (resetBest) {
            check(value == 0) { "A full Battle Tower reset must set the current streak to zero" }
            BattleRecordService.resetWinStreak(player.server, key, resetBest = true)
        } else {
            BattleRecordService.setCurrentWinStreak(player.server, key, value)
        }
        val progress = TowerProgressRecordCodec.decode(stats)
        check(sessions.adminSetProgress(player.uuid, progress)) {
            "Battle Tower session became active during an administrator progress update"
        }
        sessions.current(player.uuid)?.let { updated ->
            if (ServerPlayNetworking.canSend(player, TowerPlayStatePayload.TYPE)) {
                ServerPlayNetworking.send(player, TowerPlayStatePayload(null, updated))
            }
        }
        return BattleProgressSetResult.Applied(
            previousCurrent = before.currentWinStreak.toLong(),
            previousBest = before.bestWinStreak.toLong(),
            current = stats.currentWinStreak.toLong(),
            best = stats.bestWinStreak.toLong(),
        )
    }

    fun adminGetStreak(player: ServerPlayer, format: TowerBattleFormat): BattleProgressSetResult {
        onlinePlayers[player.uuid] = player
        if (!BattleRecordService.isAvailable(player.server)) {
            return BattleProgressSetResult.StorageUnavailable
        }
        val stats = BattleRecordService.get(
            player.server,
            BattleRecordKey(
                player.uuid,
                BattleRecordCategory(TowerRecordContract.CONTENT_ID, format.recordId),
            ),
        )
        return BattleProgressSetResult.Applied(
            previousCurrent = stats.currentWinStreak.toLong(),
            previousBest = stats.bestWinStreak.toLong(),
            current = stats.currentWinStreak.toLong(),
            best = stats.bestWinStreak.toLong(),
        )
    }

    override fun open(playerId: java.util.UUID, format: TowerBattleFormat): Boolean {
        val player = onlinePlayers[playerId] ?: return false
        return open(player, format)
    }

    override fun abandon(playerId: java.util.UUID): TowerSessionAbandonResult =
        sessions.abandonSession(playerId) { battleId ->
            Cobblemon173BattleForfeit.request(playerId, battleId)
        }

    /**
     * The Cobblemon battle screen replaces the Battle Tower screen while a battle runs and leaves the
     * client on an empty screen once it ends, so the settled session state is pushed back to reopen it.
     * Abandoned or already-detached sessions intentionally stay closed.
     */
    private fun reopenScreen(playerId: java.util.UUID, completion: TowerPlayBattleCompletionResult) {
        if (completion !is TowerPlayBattleCompletionResult.Completed) return
        val player = onlinePlayers[playerId] ?: return
        if (!ServerPlayNetworking.canSend(player, TowerPlayStatePayload.TYPE)) return
        val balance = BattlePointService.balance(player.server, playerId)
        val settled = sessions.refreshBpBalance(playerId, balance) ?: completion.state.copy(bpBalance = balance)
        ServerPlayNetworking.send(player, TowerPlayStatePayload(null, settled))
    }

    private fun submitCompletion(
        server: MinecraftServer,
        completion: PendingTowerCompletion,
    ) {
        pendingCompletions.submit(completion) { settleCompletion(server, it) }
    }

    private fun processPendingCompletions(
        server: MinecraftServer,
        force: Boolean = false,
    ) {
        pendingCompletions.retryDue(force) { settleCompletion(server, it) }
    }

    private fun settleCompletion(
        server: MinecraftServer,
        pending: PendingTowerCompletion,
    ): Boolean {
        var waitsForLaunchCommit = false
        val settled = attemptBattleCompletionSettlement(
            settle = {
                if (pending.outcome == null) {
                    sessions.cancelBattle(pending.playerId, pending.battleId, completionSink(server, pending.battleId))
                } else {
                    sessions.completeBattle(
                        pending.playerId,
                        pending.battleId,
                        pending.outcome,
                        completionSink(server, pending.battleId),
                    )
                }
            },
            afterSettlement = { completion ->
                if (completion is TowerPlayBattleCompletionResult.Completed) {
                    onlinePlayers[pending.playerId]?.let(BattleHubNetworking::sendHeader)
                    reopenScreen(pending.playerId, completion)
                } else if (completion is TowerPlayBattleCompletionResult.NoActiveBattle &&
                    sessions.isLaunchPending(pending.playerId)
                ) {
                    waitsForLaunchCommit = true
                } else if (completion is TowerPlayBattleCompletionResult.StaleBattle ||
                    completion is TowerPlayBattleCompletionResult.SessionNotFound ||
                    completion is TowerPlayBattleCompletionResult.NoActiveBattle
                ) {
                    MoreBattleContent.LOGGER.warn(
                        "Dropping stale Battle Tower completion retry for player {} and battle {}",
                        pending.playerId,
                        pending.battleId,
                    )
                }
            },
            reportSettlementFailure = { failure -> reportTowerCompletionFailure(pending, failure) },
            reportNotificationFailure = { failure -> reportTowerCompletionNotificationFailure(pending, failure) },
        )
        return try {
            finalizeCompletionOwner(
                settled = settled && !waitsForLaunchCommit,
                ownerOnline = pending.playerId in onlinePlayers,
            ) {
                runManagedCleanupActions(
                    { sessions.close(pending.playerId) },
                    { launcher.forget(pending.playerId) },
                )
            }
        } catch (failure: RuntimeException) {
            reportTowerCompletionFailure(pending, failure)
            false
        } catch (failure: LinkageError) {
            reportTowerCompletionFailure(pending, failure)
            false
        }
    }

    private fun reportTowerCompletionFailure(pending: PendingTowerCompletion, failure: Throwable) {
        reportManagedCleanupFailureSafely(failure) {
            MoreBattleContent.LOGGER.error(
                "Battle Tower settlement failed for player {} and battle {}; retrying in {} ms",
                pending.playerId,
                pending.battleId,
                COMPLETION_RETRY_MILLIS,
                it,
            )
        }
    }

    private fun reportTowerCompletionNotificationFailure(pending: PendingTowerCompletion, failure: Throwable) {
        MoreBattleContent.LOGGER.error(
            "Battle Tower result settled for player {} and battle {}, but its client update failed",
            pending.playerId,
            pending.battleId,
            failure,
        )
    }

    private data class PendingTowerCompletion(
        val playerId: java.util.UUID,
        val battleId: java.util.UUID,
        val outcome: TowerBattleOutcome?,
    )

    private fun completionSink(server: MinecraftServer, battleId: java.util.UUID) =
        TowerPlayBattleCompletionSink { recordedPlayerId, update ->
            if (update.outcome == TowerBattleOutcome.WIN) {
                BattlePointRewardSettlementService { request ->
                    BattlePointService.apply(server, request)
                }.settle(
                    BattlePointRewardSettlement(
                        settlementId = battleId,
                        playerId = recordedPlayerId,
                        contentId = TOWER_CONTENT_ID,
                        amount = update.rewardBp.toLong(),
                        reason = "${TOWER_CONTENT_ID.value}_${update.after.currentWinStreak}_streak_win",
                    ),
                ).requireAcceptedReward()
            }
            val recorded = TowerBattleRecordService { completion ->
                BattleRecordService.recordCompletedBattle(server, completion)
            }.record(recordedPlayerId, update)
            check(TowerProgressRecordCodec.decode(recorded) == update.after) {
                "Battle Tower record storage did not accept the completed progress update"
            }
        }

    private val TOWER_CONTENT_ID = BattleContentId("battle_tower")
}

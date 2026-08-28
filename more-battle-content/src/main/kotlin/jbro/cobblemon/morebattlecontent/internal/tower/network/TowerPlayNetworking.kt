package jbro.cobblemon.morebattlecontent.internal.tower.network

import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.application.BattleContentId
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
import jbro.cobblemon.morebattlecontent.internal.compat.fabric.TowerOpponentCatalogResources
import jbro.cobblemon.morebattlecontent.internal.compat.fabric.dispatchToServerThread
import jbro.cobblemon.morebattlecontent.internal.command.BattleProgressSetResult
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordCategory
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordKey
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordService
import jbro.cobblemon.morebattlecontent.internal.hub.BattleHubNetworking
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleRecordService
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
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.level.ServerPlayer
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import kotlin.random.Random

internal object TowerPlayNetworking : BattleTowerApplicationBackend {
    private val onlinePlayers = HashMap<java.util.UUID, ServerPlayer>()
    private val registeredTeamSnapshots = Cobblemon173TowerRegisteredTeamSnapshotStore(onlinePlayers::get)
    private val runtime: Cobblemon173TowerPveBattleRuntime by lazy {
        Cobblemon173TowerPveBattleRuntime(
            playerResolver = onlinePlayers::get,
            sessionCompletion = { server, playerId, battleId, outcome ->
                val completion = sessions.completeBattle(
                    playerId,
                    battleId,
                    outcome,
                    completionSink(server, battleId),
                )
                onlinePlayers[playerId]?.let(BattleHubNetworking::sendHeader)
                reopenScreen(playerId, completion)
            },
            sessionCancellation = { server, playerId, battleId ->
                val completion = sessions.cancelBattle(playerId, battleId, completionSink(server, battleId))
                reopenScreen(playerId, completion)
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
            try {
                val currentParty = if (payload.intent is jbro.cobblemon.morebattlecontent.internal.tower.ui.TowerPlayIntent.LockTeam) {
                    Cobblemon173TowerPlayOpenRequestFactory.readParty(player)
                } else {
                    null
                }
                when (val result = sessions.mutate(player.uuid, payload.intent, currentParty)) {
                    is TowerPlayMutationResult.Accepted ->
                        ServerPlayNetworking.send(player, TowerPlayStatePayload(result.requestId, result.state))

                    is TowerPlayMutationResult.Rejected ->
                        ServerPlayNetworking.send(player, TowerPlayRejectedPayload(result))
                }
            } catch (exception: RuntimeException) {
                MoreBattleContent.LOGGER.error("Battle Tower screen mutation failed for ${player.uuid}", exception)
                ServerPlayNetworking.send(
                    player,
                    TowerPlayRejectedPayload(
                        TowerPlayMutationResult.Rejected(
                            payload.intent.requestId,
                            sessions.current(player.uuid)?.revision ?: payload.intent.expectedRevision,
                            "screen.${MoreBattleContent.MOD_ID}.tower.error.internal_failure",
                        ),
                    ),
                )
            }
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            onlinePlayers[handler.player.uuid] = handler.player
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            val playerId = handler.player.uuid
            dispatchToServerThread(server.isSameThread, { action -> server.execute(action) }) {
                try {
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
                } catch (exception: RuntimeException) {
                    MoreBattleContent.LOGGER.error("Battle Tower disconnect settlement failed for $playerId", exception)
                } finally {
                    sessions.close(playerId)
                    launcher.forget(playerId)
                    onlinePlayers.remove(playerId)
                }
            }
        }
    }

    fun open(
        player: ServerPlayer,
        initialFormat: TowerBattleFormat = TowerBattleFormat.SINGLE,
        entryContext: TowerPlayEntryContext? = null,
    ): Boolean {
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
            MoreBattleContent.LOGGER.error("Battle Tower screen could not be opened for ${player.uuid}", exception)
            false
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

    private fun completionSink(server: net.minecraft.server.MinecraftServer, battleId: java.util.UUID) =
        TowerPlayBattleCompletionSink { recordedPlayerId, update ->
            if (update.outcome == jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleOutcome.WIN) {
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

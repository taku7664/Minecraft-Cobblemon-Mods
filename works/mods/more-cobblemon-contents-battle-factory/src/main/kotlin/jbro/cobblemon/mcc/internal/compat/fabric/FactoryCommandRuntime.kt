package jbro.cobblemon.mcc.internal.compat.fabric

import jbro.cobblemon.mcc.api.access.BattleContentAccess
import jbro.cobblemon.mcc.internal.factory.FactoryProgressCommands
import jbro.cobblemon.mcc.internal.command.MccAdminSource
import jbro.cobblemon.mcc.internal.command.MccAdminSources
import jbro.cobblemon.mcc.internal.command.MccCommandContributors
import jbro.cobblemon.mcc.internal.command.MccPendingResult
import net.minecraft.network.chat.Component
import jbro.cobblemon.mcc.internal.hub.BattleHubEntry
import jbro.cobblemon.mcc.internal.hub.BattleHubEntries
import jbro.cobblemon.mcc.api.access.ContentAccessAction
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds

import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import java.util.UUID
import jbro.cobblemon.mcc.internal.command.FactoryCommandBackend
import jbro.cobblemon.mcc.internal.command.BattleProgressSetResult
import jbro.cobblemon.mcc.internal.application.BattleContentId
import jbro.cobblemon.mcc.internal.battle.BattleCompletionRetryQueue
import jbro.cobblemon.mcc.internal.battle.finalizeCompletionOwner
import jbro.cobblemon.mcc.internal.bp.BattlePointRewardSettlement
import jbro.cobblemon.mcc.internal.bp.BattlePointRewardSettlementService
import jbro.cobblemon.mcc.internal.bp.BattlePointService
import jbro.cobblemon.mcc.internal.bp.requireAcceptedReward
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173FactoryPokemonFactory
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173FactoryPveBattleRuntime
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173ManagedBattleTermination
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173BattleForfeit
import jbro.cobblemon.mcc.internal.compat.cobblemon173.reportManagedCleanupFailureSafely
import jbro.cobblemon.mcc.internal.compat.cobblemon173.runManagedCleanupActionsSafely
import jbro.cobblemon.mcc.internal.factory.FactoryBattleCompletionService
import jbro.cobblemon.mcc.internal.factory.FactoryBattleCompletionResult
import jbro.cobblemon.mcc.internal.factory.FactoryBattleFormat
import jbro.cobblemon.mcc.internal.factory.FactoryBattleRecordService
import jbro.cobblemon.mcc.internal.factory.FactoryCatalogRandom
import jbro.cobblemon.mcc.internal.factory.FactoryDraftOfferService
import jbro.cobblemon.mcc.internal.factory.FactoryLevelMode
import jbro.cobblemon.mcc.internal.factory.FactoryOpponentObservation
import jbro.cobblemon.mcc.internal.factory.FactoryPlayError
import jbro.cobblemon.mcc.internal.factory.FactoryPlayResult
import jbro.cobblemon.mcc.internal.factory.FactoryPlayService
import jbro.cobblemon.mcc.internal.factory.FactoryPveBattleLauncher
import jbro.cobblemon.mcc.internal.factory.FactoryRecordContract
import jbro.cobblemon.mcc.internal.factory.FactoryRentalSet
import jbro.cobblemon.mcc.internal.factory.FactoryRunBattleService
import jbro.cobblemon.mcc.internal.factory.FactorySessionService
import jbro.cobblemon.mcc.internal.factory.FactorySessionCompletionResult
import jbro.cobblemon.mcc.internal.factory.network.FactoryPlayNetworking
import jbro.cobblemon.mcc.internal.record.BattleRecordService
import jbro.cobblemon.mcc.internal.record.BattleRecordCategory
import jbro.cobblemon.mcc.internal.record.BattleRecordKey
import jbro.cobblemon.mcc.internal.record.BattleRecordMetrics
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import kotlin.random.Random

internal object FactoryCommandRuntime : FactoryCommandBackend {
    private const val COMPLETION_RETRY_MILLIS = 5_000L
    private val onlinePlayers = HashMap<UUID, ServerPlayer>()
    private var currentServer: MinecraftServer? = null
    private val pendingCompletions = BattleCompletionRetryQueue<UUID, PendingFactoryCompletion>(
        keyOf = PendingFactoryCompletion::battleId,
        retryMillis = COMPLETION_RETRY_MILLIS,
    )
    private val random = object : FactoryCatalogRandom {
        override fun nextLong(bound: Long): Long = Random.Default.nextLong(bound)
        override fun nextInt(bound: Int): Int = Random.Default.nextInt(bound)
    }
    private val runtime: Cobblemon173FactoryPveBattleRuntime by lazy {
        Cobblemon173FactoryPveBattleRuntime(
            playerResolver = onlinePlayers::get,
            victory = { server, playerId, runId, battleId, opponentSets, observations ->
                submitCompletion(
                    server,
                    PendingFactoryCompletion.Victory(playerId, runId, battleId, opponentSets, observations),
                )
            },
            loss = { server, playerId, runId, battleId ->
                submitCompletion(server, PendingFactoryCompletion.Loss(playerId, runId, battleId))
            },
            cancellation = { _, playerId, runId, battleId ->
                sessions.cancelBattle(playerId, runId, battleId)
                pushState(playerId)
            },
        )
    }
    private val launcher by lazy {
        FactoryPveBattleLauncher<BattlePokemon>(
            playerMemberFactory = Cobblemon173FactoryPokemonFactory::toPlayerBattlePokemon,
            opponentMemberFactory = Cobblemon173FactoryPokemonFactory::toOpponentBattlePokemon,
            runtime = runtime,
            diagnostics = { reason -> jbro.cobblemon.mcc.MoreCobblemonContents.LOGGER.error("Battle Factory launch failed: {}", reason) },
        )
    }
    private val draftOffers by lazy {
        FactoryDraftOfferService(FactoryCatalogResources.store::snapshot, random)
    }
    private val sessions: FactorySessionService by lazy {
        FactorySessionService(
            runBattles = FactoryRunBattleService(launcher),
            completions = FactoryBattleCompletionService(
                FactoryBattleRecordService { completion ->
                    val server = checkNotNull(currentServer) { "Factory server is unavailable during record settlement" }
                    BattleRecordService.recordCompletedBattle(server, completion)
                },
                victoryRewards = { playerId, battleId, bp ->
                    val server = checkNotNull(currentServer) { "Factory server is unavailable during reward settlement" }
                    BattlePointRewardSettlementService { request ->
                        BattlePointService.apply(server, request)
                    }.settle(
                        BattlePointRewardSettlement(
                            settlementId = battleId,
                            playerId = playerId,
                            contentId = FACTORY_CONTENT_ID,
                            amount = bp,
                            reason = "${FACTORY_CONTENT_ID.value.substringAfter(':')}_win",
                        ),
                    ).requireAcceptedReward()
                },
            ),
            draftProvider = draftOffers::select,
        )
    }
    private val play: FactoryPlayService by lazy {
        FactoryPlayService(
            FactoryCatalogResources.store::snapshot,
            sessions,
            random,
            draftOffers,
        ) { playerId, format, levelMode ->
            val player = onlinePlayers[playerId] ?: return@FactoryPlayService 0
            BattleRecordService.get(
                player.server,
                BattleRecordKey(
                    playerId,
                    BattleRecordCategory(FactoryRecordContract.CONTENT_ID, format.recordId(levelMode)),
                ),
            ).progressMetrics[BattleRecordMetrics.CURRENT_FLOOR]
                ?.coerceAtMost(Int.MAX_VALUE.toLong())
                ?.toInt()
                ?: 0
        }
    }

    fun registerServer() {
        play
        MccCommandContributors.register { FactoryProgressCommands.build() }
        MccAdminSources.register(adminSource)
        BattleHubEntries.register(BattleHubEntry(ManagedBattleContentIds.BATTLE_FACTORY) { player, _ -> open(player) })
        FactoryPlayNetworking.registerServer(this)
        ServerLifecycleEvents.SERVER_STARTING.register { server -> currentServer = server }
        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            runManagedCleanupActionsSafely(
                reportFailure = { failure ->
                    jbro.cobblemon.mcc.MoreCobblemonContents.LOGGER.error(
                        "Battle Factory server shutdown cleanup failed",
                        failure,
                    )
                },
                { processPendingCompletions(server, force = true) },
                {
                    play.activeBattleIds().forEach { battleId ->
                        runManagedCleanupActionsSafely(
                            reportFailure = { failure ->
                                jbro.cobblemon.mcc.MoreCobblemonContents.LOGGER.error(
                                    "Battle Factory battle $battleId could not be terminated during server shutdown",
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
                        jbro.cobblemon.mcc.MoreCobblemonContents.LOGGER.error(
                            "Discarding {} Battle Factory completion retries because the server is stopping and record storage is still unavailable",
                            pendingCompletions.size(),
                        )
                    }
                },
                pendingCompletions::clear,
                play::clear,
                onlinePlayers::clear,
                { if (currentServer === server) currentServer = null },
            )
        }
        ServerTickEvents.END_SERVER_TICK.register { server ->
            currentServer = server
            processPendingCompletions(server)
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            onlinePlayers[handler.player.uuid] = handler.player
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            val playerId = handler.player.uuid
            dispatchToServerThread(server.isSameThread, { action -> server.execute(action) }) {
                processPendingCompletions(server, force = true)
                runManagedCleanupActionsSafely(
                    reportFailure = { failure ->
                        jbro.cobblemon.mcc.MoreCobblemonContents.LOGGER.error(
                            "Battle Factory disconnect cleanup failed for $playerId",
                            failure,
                        )
                    },
                    {
                        if (!pendingCompletions.any { it.playerId == playerId }) {
                            play.disconnect(playerId, Cobblemon173ManagedBattleTermination::end)
                        }
                    },
                    { onlinePlayers.remove(playerId) },
                )
            }
        }
    }

    override fun open(player: ServerPlayer): Boolean = withPlayer(player) {
        if (!BattleContentAccess.allow(player, ManagedBattleContentIds.BATTLE_FACTORY, ContentAccessAction.OPEN)) return@withPlayer false
        FactoryPlayNetworking.open(player)
    }

    override fun start(
        player: ServerPlayer,
        format: FactoryBattleFormat,
        levelMode: FactoryLevelMode,
    ): FactoryPlayResult = mutate(player) { play.start(player.uuid, format, levelMode) }

    override fun select(player: ServerPlayer, setIds: List<String>): FactoryPlayResult =
        mutate(player) { play.selectDraft(player.uuid, setIds) }

    override fun revise(player: ServerPlayer): FactoryPlayResult =
        mutate(player) { play.reviseSelection(player.uuid) }

    override fun battle(player: ServerPlayer, orderedSetIds: List<String>?): FactoryPlayResult =
        mutate(player) { play.beginBattle(player.uuid, orderedSetIds) }

    override fun keep(player: ServerPlayer): FactoryPlayResult =
        mutate(player) { play.keepTeam(player.uuid) }

    override fun swap(player: ServerPlayer, outgoingSetId: String, incomingToken: UUID): FactoryPlayResult =
        mutate(player) { play.swap(player.uuid, outgoingSetId, incomingToken) }

    override fun status(player: ServerPlayer): FactoryPlayResult =
        withPlayer(player) { FactoryPlayResult.Accepted(play.status(player.uuid)) }

    override fun abandon(player: ServerPlayer): FactoryPlayResult = withPlayer(player) {
        val current = play.status(player.uuid)
        val battleId = current.activeBattleId
        if (battleId != null) {
            if (!Cobblemon173BattleForfeit.request(player.uuid, battleId)) {
                FactoryPlayResult.Rejected(FactoryPlayError.BATTLE_UNAVAILABLE)
            } else {
                FactoryPlayResult.Accepted(current)
            }
        } else {
            FactoryPlayResult.Accepted(play.abandon(player.uuid))
        }
    }

    /** How an operator's abandon of a Battle Factory run went. */
    enum class AdminAbandon { NO_SESSION, CLOSED, FORFEITED, FORFEIT_UNAVAILABLE }

    /** What an operator sees of [playerId]'s run, or null when they have none. */
    fun adminDescribe(playerId: UUID): List<Component>? {
        val run = sessions.snapshot(playerId) ?: return null
        val key = "command.${jbro.cobblemon.mcc.MoreCobblemonContents.MOD_ID}.factory.admin"
        val team = run.teamSets.joinToString(", ") { it.speciesId.substringAfter(':') }.ifEmpty { "-" }
        return listOf(
            Component.translatable("$key.session", run.format.recordId(run.levelMode), run.phase.name.lowercase()),
            Component.translatable("$key.wins", run.wins, run.rentAndTradeCount),
            Component.translatable("$key.team", team),
            Component.translatable("$key.battle", run.activeBattleId?.toString()?.take(8) ?: "-",
                sessions.isLaunchPending(playerId, run.runId).toString(), pendingCompletions.completions().count { it.playerId == playerId }),
        )
    }

    /**
     * Ends [playerId]'s run for an operator. Without [force] it is the player's own abandon: a battle in progress
     * is forfeited and counts as a loss. With [force] a battle is ended without a result and the run is dropped.
     */
    fun adminAbandon(playerId: UUID, force: Boolean): AdminAbandon {
        val run = sessions.snapshot(playerId) ?: return AdminAbandon.NO_SESSION
        val battleId = run.activeBattleId
        val result = when {
            battleId != null && !force ->
                if (Cobblemon173BattleForfeit.request(playerId, battleId)) AdminAbandon.FORFEITED else AdminAbandon.FORFEIT_UNAVAILABLE
            else -> {
                battleId?.let(Cobblemon173ManagedBattleTermination::end)
                play.abandon(playerId)
                AdminAbandon.CLOSED
            }
        }
        pushState(playerId)
        return result
    }

    private val adminSource = object : MccAdminSource {
        override val label: Component = Component.translatable("command.${jbro.cobblemon.mcc.MoreCobblemonContents.MOD_ID}.factory.admin.label")

        override fun status(server: MinecraftServer): List<Component> {
            val key = "command.${jbro.cobblemon.mcc.MoreCobblemonContents.MOD_ID}.factory.admin.status"
            val catalog = FactoryCatalogResources.store.snapshot()
            return listOf(
                if (catalog == null) Component.translatable("$key.catalog_missing")
                else Component.translatable("$key.catalog", catalog.catalogId, catalog.trainerCount, catalog.setCount),
                Component.translatable("$key.sessions", sessions.count(), play.activeBattleIds().size, pendingCompletions.size()),
            )
        }

        override fun pending(server: MinecraftServer): List<MccPendingResult> = pendingCompletions.completions().map {
            MccPendingResult(it.playerId, it.battleId, if (it is PendingFactoryCompletion.Victory) "win" else "loss")
        }

        override fun retryPending(server: MinecraftServer, playerId: UUID?): Int =
            pendingCompletions.retryMatching({ playerId == null || it.playerId == playerId }) { settleCompletion(server, it) }

        override fun dropPending(server: MinecraftServer, playerId: UUID?): Int =
            pendingCompletions.drop { playerId == null || it.playerId == playerId }

        override fun busy(server: MinecraftServer, playerId: UUID): Boolean =
            sessions.snapshot(playerId) != null || pendingCompletions.any { it.playerId == playerId }
    }

    fun adminSetFloor(
        player: ServerPlayer,
        format: FactoryBattleFormat,
        levelMode: FactoryLevelMode,
        value: Int,
        resetBest: Boolean = false,
    ): BattleProgressSetResult = withPlayer(player) {
        val active = sessions.snapshot(player.uuid)
        val matchingSession = active?.format == format && active.levelMode == levelMode
        if (matchingSession && active.activeBattleId != null) {
            return@withPlayer BattleProgressSetResult.ActiveBattle
        }
        if (!BattleRecordService.isAvailable(player.server)) {
            return@withPlayer BattleProgressSetResult.StorageUnavailable
        }
        val key = BattleRecordKey(
            player.uuid,
            BattleRecordCategory(FactoryRecordContract.CONTENT_ID, format.recordId(levelMode)),
        )
        val before = BattleRecordService.get(player.server, key)
        val stats = if (resetBest) {
            check(value == 0) { "A full Battle Factory reset must set the current floor to zero" }
            BattleRecordService.resetProgressAndBestMetric(
                player.server,
                key,
                BattleRecordMetrics.CURRENT_FLOOR,
                BattleRecordMetrics.HIGHEST_FLOOR,
                resetBest = true,
            )
        } else {
            BattleRecordService.setProgressAndBestMetric(
                player.server,
                key,
                BattleRecordMetrics.CURRENT_FLOOR,
                BattleRecordMetrics.HIGHEST_FLOOR,
                value.toLong(),
            )
        }
        check(play.adminSetWins(player.uuid, format, levelMode, value)) {
            "Battle Factory session became active during an administrator progress update"
        }
        pushState(player.uuid)
        BattleProgressSetResult.Applied(
            previousCurrent = before.progressMetrics[BattleRecordMetrics.CURRENT_FLOOR] ?: 0L,
            previousBest = before.bestMetrics[BattleRecordMetrics.HIGHEST_FLOOR] ?: 0L,
            current = stats.progressMetrics.getValue(BattleRecordMetrics.CURRENT_FLOOR),
            best = stats.bestMetrics[BattleRecordMetrics.HIGHEST_FLOOR] ?: 0L,
        )
    }

    fun adminGetFloor(
        player: ServerPlayer,
        format: FactoryBattleFormat,
        levelMode: FactoryLevelMode,
    ): BattleProgressSetResult = withPlayer(player) {
        if (!BattleRecordService.isAvailable(player.server)) {
            return@withPlayer BattleProgressSetResult.StorageUnavailable
        }
        val stats = BattleRecordService.get(
            player.server,
            BattleRecordKey(
                player.uuid,
                BattleRecordCategory(FactoryRecordContract.CONTENT_ID, format.recordId(levelMode)),
            ),
        )
        val current = stats.progressMetrics[BattleRecordMetrics.CURRENT_FLOOR] ?: 0L
        val best = stats.bestMetrics[BattleRecordMetrics.HIGHEST_FLOOR] ?: 0L
        BattleProgressSetResult.Applied(current, best, current, best)
    }

    private inline fun <T> withPlayer(player: ServerPlayer, operation: () -> T): T {
        onlinePlayers[player.uuid] = player
        return operation()
    }

    private inline fun mutate(player: ServerPlayer, operation: () -> FactoryPlayResult): FactoryPlayResult = withPlayer(player) {
        if (!BattleContentAccess.allow(player, ManagedBattleContentIds.BATTLE_FACTORY, ContentAccessAction.MUTATE)) {
            FactoryPlayResult.Rejected(FactoryPlayError.BATTLE_UNAVAILABLE)
        } else operation()
    }

    private fun pushState(playerId: UUID) {
        onlinePlayers[playerId]?.let(FactoryPlayNetworking::push)
    }

    private fun submitCompletion(server: MinecraftServer, completion: PendingFactoryCompletion) {
        currentServer = server
        pendingCompletions.submit(completion) { settleCompletion(server, it) }
    }

    private fun processPendingCompletions(server: MinecraftServer, force: Boolean = false) {
        currentServer = server
        pendingCompletions.retryDue(force) { settleCompletion(server, it) }
    }

    private fun settleCompletion(server: MinecraftServer, completion: PendingFactoryCompletion): Boolean {
        currentServer = server
        return try {
            val result = when (completion) {
                is PendingFactoryCompletion.Victory -> sessions.completeVictory(
                    completion.playerId,
                    completion.runId,
                    completion.battleId,
                    completion.opponentSets,
                    completion.observations,
                )
                is PendingFactoryCompletion.Loss -> sessions.completeLoss(
                    completion.playerId,
                    completion.runId,
                    completion.battleId,
                )
            }
            if (result is FactorySessionCompletionResult.Completed &&
                result.result is FactoryBattleCompletionResult.NoActiveBattle &&
                sessions.isLaunchPending(completion.playerId, completion.runId)
            ) {
                return false
            }
            if (result is FactorySessionCompletionResult.Completed &&
                result.result !is FactoryBattleCompletionResult.StaleBattle &&
                result.result !is FactoryBattleCompletionResult.NoActiveBattle
            ) {
                onlinePlayers[completion.playerId]?.let { player ->
                    val opponent = jbro.cobblemon.mcc.api.presentation.ManagedBattleOpponents.name(completion.battleId)
                    val victory = result.result as? FactoryBattleCompletionResult.Victory
                    if (completion is PendingFactoryCompletion.Victory && victory != null) {
                        jbro.cobblemon.mcc.api.presentation.BattleResultNotices.victory(player, opponent, victory.rewardBp)
                    } else {
                        jbro.cobblemon.mcc.api.presentation.BattleResultNotices.defeat(player, opponent)
                    }
                }
                pushState(completion.playerId)
            } else if (result !is FactorySessionCompletionResult.Completed) {
                jbro.cobblemon.mcc.MoreCobblemonContents.LOGGER.warn(
                    "Dropping stale Battle Factory completion retry for player {} and battle {}",
                    completion.playerId,
                    completion.battleId,
                )
            }
            finalizeCompletionOwner(
                settled = true,
                ownerOnline = completion.playerId in onlinePlayers,
            ) {
                play.disconnect(completion.playerId)
            }
        } catch (failure: RuntimeException) {
            reportCompletionFailure(completion, failure)
            false
        } catch (failure: LinkageError) {
            reportCompletionFailure(completion, failure)
            false
        }
    }

    private fun reportCompletionFailure(completion: PendingFactoryCompletion, failure: Throwable) {
        reportManagedCleanupFailureSafely(failure) {
            jbro.cobblemon.mcc.MoreCobblemonContents.LOGGER.error(
                "Battle Factory settlement failed for player {} and battle {}; retrying in {} ms",
                completion.playerId,
                completion.battleId,
                COMPLETION_RETRY_MILLIS,
                it,
            )
        }
    }

    private sealed interface PendingFactoryCompletion {
        val playerId: UUID
        val runId: UUID
        val battleId: UUID

        data class Victory(
            override val playerId: UUID,
            override val runId: UUID,
            override val battleId: UUID,
            val opponentSets: Map<UUID, FactoryRentalSet>,
            val observations: Map<String, FactoryOpponentObservation>,
        ) : PendingFactoryCompletion

        data class Loss(
            override val playerId: UUID,
            override val runId: UUID,
            override val battleId: UUID,
        ) : PendingFactoryCompletion
    }

    private val FACTORY_CONTENT_ID = BattleContentId(FactoryRecordContract.CONTENT_ID)
}

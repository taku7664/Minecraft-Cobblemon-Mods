package jbro.cobblemon.morebattlecontent.internal.compat.fabric

import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.command.FactoryCommandBackend
import jbro.cobblemon.morebattlecontent.internal.application.BattleContentId
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointRewardSettlementService
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointService
import jbro.cobblemon.morebattlecontent.internal.bp.requireAcceptedReward
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.Cobblemon173FactoryPokemonFactory
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.Cobblemon173FactoryPveBattleRuntime
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.Cobblemon173BattleForfeit
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryBattleCompletionService
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryBattleFormat
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryBattleRecordService
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryCatalogRandom
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryDraftOfferService
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryLevelMode
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryPlayError
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryPlayResult
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryPlayService
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryPveBattleLauncher
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryRecordContract
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryRunBattleService
import jbro.cobblemon.morebattlecontent.internal.factory.FactorySessionService
import jbro.cobblemon.morebattlecontent.internal.factory.network.FactoryPlayNetworking
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordService
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.level.ServerPlayer
import kotlin.random.Random

internal object FactoryCommandRuntime : FactoryCommandBackend {
    private val onlinePlayers = HashMap<UUID, ServerPlayer>()
    private val random = object : FactoryCatalogRandom {
        override fun nextLong(bound: Long): Long = Random.Default.nextLong(bound)
        override fun nextInt(bound: Int): Int = Random.Default.nextInt(bound)
    }
    private val runtime: Cobblemon173FactoryPveBattleRuntime by lazy {
        Cobblemon173FactoryPveBattleRuntime(
            playerResolver = onlinePlayers::get,
            victory = { _, playerId, runId, battleId, opponentSets, observations ->
                sessions.completeVictory(playerId, runId, battleId, opponentSets, observations)
                pushState(playerId)
            },
            loss = { _, playerId, runId, battleId ->
                sessions.completeLoss(playerId, runId, battleId)
                pushState(playerId)
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
                    val player = checkNotNull(onlinePlayers[completion.key.playerId]) {
                        "Factory record owner disconnected before completion"
                    }
                    BattleRecordService.recordCompletedBattle(player.server, completion)
                },
                victoryRewards = { playerId, battleId ->
                    val player = checkNotNull(onlinePlayers[playerId]) {
                        "Factory reward owner disconnected before settlement"
                    }
                    BattlePointRewardSettlementService { request ->
                        BattlePointService.apply(player.server, request)
                    }.settleVictory(battleId, playerId, FACTORY_CONTENT_ID).requireAcceptedReward()
                },
            ),
            draftProvider = draftOffers::select,
        )
    }
    private val play: FactoryPlayService by lazy {
        FactoryPlayService(FactoryCatalogResources.store::snapshot, sessions, random, draftOffers)
    }

    fun registerServer() {
        play
        FactoryPlayNetworking.registerServer(this)
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            onlinePlayers[handler.player.uuid] = handler.player
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            val playerId = handler.player.uuid
            try {
                play.disconnect(playerId)
            } catch (exception: RuntimeException) {
                jbro.cobblemon.morebattlecontent.MoreBattleContent.LOGGER.error(
                    "Battle Factory disconnect settlement failed for $playerId",
                    exception,
                )
            } finally {
                onlinePlayers.remove(playerId)
            }
        }
    }

    override fun open(player: ServerPlayer): Boolean = withPlayer(player) {
        FactoryPlayNetworking.open(player)
    }

    override fun start(
        player: ServerPlayer,
        format: FactoryBattleFormat,
        levelMode: FactoryLevelMode,
    ): FactoryPlayResult = withPlayer(player) { play.start(player.uuid, format, levelMode) }

    override fun select(player: ServerPlayer, setIds: List<String>): FactoryPlayResult =
        withPlayer(player) { play.selectDraft(player.uuid, setIds) }

    override fun revise(player: ServerPlayer): FactoryPlayResult =
        withPlayer(player) { play.reviseSelection(player.uuid) }

    override fun battle(player: ServerPlayer, orderedSetIds: List<String>?): FactoryPlayResult =
        withPlayer(player) { play.beginBattle(player.uuid, orderedSetIds) }

    override fun keep(player: ServerPlayer): FactoryPlayResult =
        withPlayer(player) { play.keepTeam(player.uuid) }

    override fun swap(player: ServerPlayer, outgoingSetId: String, incomingToken: UUID): FactoryPlayResult =
        withPlayer(player) { play.swap(player.uuid, outgoingSetId, incomingToken) }

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

    private inline fun <T> withPlayer(player: ServerPlayer, operation: () -> T): T {
        onlinePlayers[player.uuid] = player
        return operation()
    }

    private fun pushState(playerId: UUID) {
        onlinePlayers[playerId]?.let(FactoryPlayNetworking::push)
    }

    private val FACTORY_CONTENT_ID = BattleContentId(FactoryRecordContract.CONTENT_ID)
}

package jbro.cobblemon.morebattlecontent.internal.bp.shop

import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointApplyResult
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointAtomicApplier
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointRequest
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointService
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.reportManagedCleanupFailureSafely
import jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173.runManagedCleanupActionsSafely
import jbro.cobblemon.morebattlecontent.internal.compat.fabric.BattlePointShopCatalogResources
import jbro.cobblemon.morebattlecontent.internal.compat.fabric.MinecraftBattlePointShopDelivery
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryBattleFormat
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryLevelMode
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryRecordContract
import jbro.cobblemon.morebattlecontent.internal.hub.BattleHubNetworking
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordCategory
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordService
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleFormat
import jbro.cobblemon.morebattlecontent.internal.pvp.PvpBattleRecordService
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleFormat
import jbro.cobblemon.morebattlecontent.internal.tower.TowerRecordContract
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.level.ServerPlayer

internal object ShopPlayNetworking {
    fun registerServer() {
        PayloadTypeRegistry.playS2C().register(ShopStatePayload.TYPE, ShopStatePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(HomeLeaderboardStatePayload.TYPE, HomeLeaderboardStatePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(HomeLeaderboardCatalogPayload.TYPE, HomeLeaderboardCatalogPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(ShopOpenPayload.TYPE, ShopOpenPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(ShopPurchasePayload.TYPE, ShopPurchasePayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(ShopOpenPayload.TYPE) { _, context ->
            open(context.player())
        }
        ServerPlayNetworking.registerGlobalReceiver(ShopPurchasePayload.TYPE) { payload, context ->
            purchase(context.player(), payload)
        }
    }

    fun open(player: ServerPlayer): Boolean {
        return try {
            if (!ServerPlayNetworking.canSend(player, ShopStatePayload.TYPE)) {
                false
            } else {
                sendState(player, null)
                sendLeaderboardSafely(player)
                true
            }
        } catch (failure: RuntimeException) {
            reportOpenFailure(player, failure)
            false
        } catch (failure: LinkageError) {
            reportOpenFailure(player, failure)
            false
        }
    }

    private fun purchase(player: ServerPlayer, payload: ShopPurchasePayload) {
        val result = try {
            val service = BattlePointShopService(
                catalog = BattlePointShopCatalogResources.store::snapshot,
                battlePoints = ServerBattlePointAtomicApplier(player),
                delivery = MinecraftBattlePointShopDelivery(player.server),
            )
            service.purchase(
                BattlePointShopPurchaseRequest(
                    purchaseId = payload.purchaseId,
                    playerId = player.uuid,
                    catalogId = payload.catalogId,
                    catalogRevision = payload.catalogRevision,
                    lines = payload.lines,
                ),
            )
        } catch (failure: RuntimeException) {
            failedPurchaseResult(player, failure)
        } catch (failure: LinkageError) {
            failedPurchaseResult(player, failure)
        }
        runManagedCleanupActionsSafely(
            reportFailure = { failure ->
                MoreBattleContent.LOGGER.error("BP shop purchase response failed for ${player.uuid}", failure)
            },
            { sendState(player, result.status) },
            { BattleHubNetworking.sendHeader(player) },
        )
    }

    private fun sendState(player: ServerPlayer, result: BattlePointShopPurchaseStatus?): Boolean {
        val catalog = BattlePointShopCatalogResources.store.snapshot()
        ServerPlayNetworking.send(
            player,
            shopStatePayload(catalog, BattlePointService.balance(player.server, player.uuid), result),
        )
        return true
    }

    private fun sendLeaderboardSafely(player: ServerPlayer) {
        runManagedCleanupActionsSafely(
            reportFailure = { failure ->
                MoreBattleContent.LOGGER.error("BP shop leaderboard response failed for ${player.uuid}", failure)
            },
            {
                if (ServerPlayNetworking.canSend(player, HomeLeaderboardStatePayload.TYPE)) {
                    val entries = leaderboardEntrySource(player)
                    ServerPlayNetworking.send(
                        player,
                        HomeLeaderboardStatePayload(
                            singles = entries(TowerRecordContract.CONTENT_ID, TowerBattleFormat.SINGLE.recordId, HomeLeaderboardRanking.TOWER),
                            doubles = entries(TowerRecordContract.CONTENT_ID, TowerBattleFormat.DOUBLE.recordId, HomeLeaderboardRanking.TOWER),
                        ),
                    )
                }
            },
            {
                if (ServerPlayNetworking.canSend(player, HomeLeaderboardCatalogPayload.TYPE)) {
                    val entries = leaderboardEntrySource(player)
                    val boards = homeLeaderboardBoardSpecs().map { spec ->
                        HomeLeaderboardBoard(
                            spec.contentId,
                            spec.formatId,
                            entries(spec.contentId, spec.formatId, spec.ranking),
                        )
                    }
                    ServerPlayNetworking.send(player, HomeLeaderboardCatalogPayload(boards))
                }
            },
        )
    }

    private fun leaderboardEntrySource(
        player: ServerPlayer,
    ): (String, String, HomeLeaderboardRanking) -> List<HomeLeaderboardEntry> {
        val server = player.server
        val onlineNames = server.playerList.players.associate { it.uuid to it.scoreboardName }
        fun name(playerId: java.util.UUID): String? =
            onlineNames[playerId] ?: server.profileCache?.get(playerId)?.orElse(null)?.name
        return { contentId, formatId, ranking ->
            HomeLeaderboard.project(
                BattleRecordService.all(server, BattleRecordCategory(contentId, formatId)),
                ranking,
                ::name,
            )
        }
    }

    private fun failedPurchaseResult(player: ServerPlayer, failure: Throwable): BattlePointShopPurchaseResult {
        reportManagedCleanupFailureSafely(failure) {
            MoreBattleContent.LOGGER.error("BP shop purchase failed for ${player.uuid}", it)
        }
        return BattlePointShopPurchaseResult(BattlePointShopPurchaseStatus.DELIVERY_FAILED)
    }

    private fun reportOpenFailure(player: ServerPlayer, failure: Throwable) {
        reportManagedCleanupFailureSafely(failure) {
            MoreBattleContent.LOGGER.error("BP shop could not be opened for ${player.uuid}", it)
        }
    }

    private class ServerBattlePointAtomicApplier(
        private val player: ServerPlayer,
    ) : BattlePointAtomicApplier {
        override fun applyAtomically(
            request: BattlePointRequest,
            commit: () -> Boolean,
        ): BattlePointApplyResult = BattlePointService.applyAtomically(player.server, request, commit)
    }
}

internal data class HomeLeaderboardBoardSpec(
    val contentId: String,
    val formatId: String,
    val ranking: HomeLeaderboardRanking,
)

internal fun homeLeaderboardBoardSpecs(): List<HomeLeaderboardBoardSpec> = buildList {
    TowerBattleFormat.entries.forEach { format ->
        add(HomeLeaderboardBoardSpec(TowerRecordContract.CONTENT_ID, format.recordId, HomeLeaderboardRanking.TOWER))
    }
    FactoryBattleFormat.entries.forEach { format ->
        FactoryLevelMode.entries.forEach { levelMode ->
            add(
                HomeLeaderboardBoardSpec(
                    FactoryRecordContract.CONTENT_ID,
                    format.recordId(levelMode),
                    HomeLeaderboardRanking.FACTORY,
                ),
            )
        }
    }
    PvpBattleFormat.entries.forEach { format ->
        add(HomeLeaderboardBoardSpec(PvpBattleRecordService.CONTENT_ID, format.recordId, HomeLeaderboardRanking.PVP))
    }
}

internal fun shopStatePayload(
    catalog: BattlePointShopCatalog?,
    balanceBp: Long,
    result: BattlePointShopPurchaseStatus?,
): ShopStatePayload = ShopStatePayload(
    catalogId = catalog?.catalogId.orEmpty(),
    catalogRevision = catalog?.revision.orEmpty(),
    balanceBp = balanceBp,
    limits = catalog?.limits ?: BattlePointShopLimits(1, 1, 1),
    entries = catalog?.entries()?.map { entry ->
        ShopEntryView(entry.entryId, entry.itemId, entry.itemCount, entry.priceBp)
    }.orEmpty(),
    result = if (catalog == null) BattlePointShopPurchaseStatus.CATALOG_UNAVAILABLE else result,
)

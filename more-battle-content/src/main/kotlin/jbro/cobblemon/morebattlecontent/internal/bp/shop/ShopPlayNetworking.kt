package jbro.cobblemon.morebattlecontent.internal.bp.shop

import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointApplyResult
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointAtomicApplier
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointRequest
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointService
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
        if (!ServerPlayNetworking.canSend(player, ShopStatePayload.TYPE)) return false
        sendState(player, null)
        sendLeaderboard(player)
        return true
    }

    private fun purchase(player: ServerPlayer, payload: ShopPurchasePayload) {
        val service = BattlePointShopService(
            catalog = BattlePointShopCatalogResources.store::snapshot,
            battlePoints = ServerBattlePointAtomicApplier(player),
            delivery = MinecraftBattlePointShopDelivery(player.server),
        )
        val result = try {
            service.purchase(
                BattlePointShopPurchaseRequest(
                    purchaseId = payload.purchaseId,
                    playerId = player.uuid,
                    catalogId = payload.catalogId,
                    catalogRevision = payload.catalogRevision,
                    lines = payload.lines,
                ),
            )
        } catch (exception: RuntimeException) {
            MoreBattleContent.LOGGER.error("BP shop purchase failed for ${player.uuid}", exception)
            BattlePointShopPurchaseResult(BattlePointShopPurchaseStatus.DELIVERY_FAILED)
        }
        sendState(player, result.status)
        BattleHubNetworking.sendHeader(player)
    }

    private fun sendState(player: ServerPlayer, result: BattlePointShopPurchaseStatus?): Boolean {
        val catalog = BattlePointShopCatalogResources.store.snapshot()
        ServerPlayNetworking.send(
            player,
            shopStatePayload(catalog, BattlePointService.balance(player.server, player.uuid), result),
        )
        return true
    }

    private fun sendLeaderboard(player: ServerPlayer) {
        val server = player.server
        val onlineNames = server.playerList.players.associate { it.uuid to it.scoreboardName }
        fun name(playerId: java.util.UUID): String? =
            onlineNames[playerId] ?: server.profileCache?.get(playerId)?.orElse(null)?.name
        fun entries(contentId: String, formatId: String, ranking: HomeLeaderboardRanking): List<HomeLeaderboardEntry> =
            HomeLeaderboard.project(
                BattleRecordService.all(server, BattleRecordCategory(contentId, formatId)),
                ranking,
                ::name,
            )
        if (ServerPlayNetworking.canSend(player, HomeLeaderboardStatePayload.TYPE)) {
            ServerPlayNetworking.send(
                player,
                HomeLeaderboardStatePayload(
                    singles = entries(TowerRecordContract.CONTENT_ID, TowerBattleFormat.SINGLE.recordId, HomeLeaderboardRanking.TOWER),
                    doubles = entries(TowerRecordContract.CONTENT_ID, TowerBattleFormat.DOUBLE.recordId, HomeLeaderboardRanking.TOWER),
                ),
            )
        }
        if (ServerPlayNetworking.canSend(player, HomeLeaderboardCatalogPayload.TYPE)) {
            val boards = homeLeaderboardBoardSpecs().map { spec ->
                HomeLeaderboardBoard(
                    spec.contentId,
                    spec.formatId,
                    entries(spec.contentId, spec.formatId, spec.ranking),
                )
            }
            ServerPlayNetworking.send(player, HomeLeaderboardCatalogPayload(boards))
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

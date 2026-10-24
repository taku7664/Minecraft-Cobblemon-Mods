package jbro.cobblemon.mcc.internal.bp.recovery

import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.bp.*
import jbro.cobblemon.mcc.internal.bp.shop.*
import jbro.cobblemon.mcc.internal.compat.fabric.MinecraftBattlePointShopDelivery
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource

internal object CompensationRuntime {
    fun service(server: MinecraftServer): CompensationService {
        check(server.isSameThread) { "Compensation requires the server thread" }
        return CompensationService(
            CompensationJournal(server.getWorldPath(LevelResource.ROOT).resolve("data/mcc-compensation-v1")),
            available = { BattlePointService.isAvailable(server) },
            receipt = { player, transaction -> BattlePointService.transaction(server, player, transaction) },
            apply = { BattlePointService.applyDirect(server, it) },
            historyHead = { BattlePointService.history(server, it, 1).firstOrNull()?.transactionId },
            operatorChanged = { BattlePointService.operatorChanged(server, it.player, it.baseline, it.baselineKnown) },
            delivery = MinecraftBattlePointShopDelivery(server),
        )
    }

    fun reward(server: MinecraftServer, request: BattlePointRequest): BattlePointApplyResult {
        return try {
            val result = service(server).reward(request)
            result.bpResult ?: BattlePointApplyResult(BattlePointApplyStatus.UNAVAILABLE, BattlePointService.balance(server, request.playerId))
        } catch (failure: Exception) {
            MoreCobblemonContents.LOGGER.error("Reward journal could not settle transaction {} for {}; operator review required",
                request.transactionId, request.playerId, failure)
            BattlePointApplyResult(BattlePointApplyStatus.UNAVAILABLE, 0)
        }
    }

    fun purchase(server: MinecraftServer, request: BattlePointRequest, grants: List<BattlePointShopGrant>): BattlePointShopPurchaseResult {
        val result = service(server).purchase(request, grants)
        val status = when (result.status) {
            CompensationService.Status.APPLIED -> BattlePointShopPurchaseStatus.APPLIED
            CompensationService.Status.ALREADY_APPLIED -> BattlePointShopPurchaseStatus.ALREADY_APPLIED
            CompensationService.Status.OPERATOR -> BattlePointShopPurchaseStatus.DELIVERY_FAILED
            CompensationService.Status.REJECTED -> BattlePointShopPurchaseStatus.INVENTORY_REJECTED
            CompensationService.Status.WAITING -> when (result.bpResult?.status) {
                BattlePointApplyStatus.INSUFFICIENT_FUNDS -> BattlePointShopPurchaseStatus.INSUFFICIENT_FUNDS
                BattlePointApplyStatus.BALANCE_OVERFLOW -> BattlePointShopPurchaseStatus.CART_OVERFLOW
                BattlePointApplyStatus.UNAVAILABLE -> BattlePointShopPurchaseStatus.BP_UNAVAILABLE
                else -> BattlePointShopPurchaseStatus.DELIVERY_FAILED
            }
        }
        return BattlePointShopPurchaseResult(status, request.operation.requestedValue, BattlePointService.balance(server, request.playerId))
    }
}

package jbro.cobblemon.morebattlecontent.internal.bp.shop

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointApplyStatus
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointAtomicApplier
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointOperation
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointRequest
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointSourceId

internal data class BattlePointShopCartLine(
    val entryId: String,
    val quantity: Int,
)

internal class BattlePointShopPurchaseRequest(
    val purchaseId: UUID,
    val playerId: UUID,
    val catalogId: String,
    val catalogRevision: String,
    lines: List<BattlePointShopCartLine>,
) {
    val lines: List<BattlePointShopCartLine> = lines.toList()
}

internal data class BattlePointShopGrant(
    val itemId: String,
    val count: Int,
)

internal interface BattlePointShopDelivery {
    fun prepare(playerId: UUID, grants: List<BattlePointShopGrant>): BattlePointShopDeliveryPlan?
}

internal interface BattlePointShopDeliveryPlan {
    fun commit(): Boolean
    fun rollback()
}

internal enum class BattlePointShopPurchaseStatus {
    APPLIED,
    ALREADY_APPLIED,
    CATALOG_UNAVAILABLE,
    STALE_CATALOG,
    INVALID_CART,
    UNKNOWN_ENTRY,
    CART_OVERFLOW,
    INSUFFICIENT_FUNDS,
    TRANSACTION_CONFLICT,
    INVENTORY_REJECTED,
    DELIVERY_FAILED,
    BP_UNAVAILABLE,
}

internal data class BattlePointShopPurchaseResult(
    val status: BattlePointShopPurchaseStatus,
    val totalCostBp: Long = 0,
    val balanceBp: Long = 0,
)

internal class BattlePointShopService(
    private val catalog: () -> BattlePointShopCatalog?,
    private val battlePoints: BattlePointAtomicApplier,
    private val delivery: BattlePointShopDelivery,
) {
    fun purchase(request: BattlePointShopPurchaseRequest): BattlePointShopPurchaseResult {
        val currentCatalog = catalog()
            ?: return BattlePointShopPurchaseResult(BattlePointShopPurchaseStatus.CATALOG_UNAVAILABLE)
        if (request.catalogId != currentCatalog.catalogId || request.catalogRevision != currentCatalog.revision) {
            return BattlePointShopPurchaseResult(BattlePointShopPurchaseStatus.STALE_CATALOG)
        }

        val resolved = resolveCart(currentCatalog, request.lines)
        if (resolved is CartResolution.Rejected) return BattlePointShopPurchaseResult(resolved.status)
        resolved as CartResolution.Accepted
        val bpRequest = BattlePointRequest(
            transactionId = request.purchaseId,
            playerId = request.playerId,
            operation = BattlePointOperation.ShopPurchase(resolved.totalCostBp),
            sourceId = SOURCE_ID,
            reason = purchaseReason(currentCatalog.catalogId, currentCatalog.revision, request.lines),
        )

        var plan: BattlePointShopDeliveryPlan? = null
        var deliveryState = DeliveryState.NOT_ATTEMPTED
        val bpResult = battlePoints.applyAtomically(bpRequest) {
            plan = try {
                delivery.prepare(request.playerId, resolved.grants)
            } catch (_: RuntimeException) {
                null
            }
            if (plan == null) {
                deliveryState = DeliveryState.PREPARE_REJECTED
                false
            } else {
                val committed = try {
                    requireNotNull(plan).commit()
                } catch (_: RuntimeException) {
                    false
                }
                deliveryState = if (committed) DeliveryState.COMMITTED else DeliveryState.COMMIT_FAILED
                committed
            }
        }

        if (bpResult.status == BattlePointApplyStatus.COMMIT_REJECTED && plan != null) {
            requireNotNull(plan).rollback()
        }
        val status = when (bpResult.status) {
            BattlePointApplyStatus.APPLIED -> BattlePointShopPurchaseStatus.APPLIED
            BattlePointApplyStatus.ALREADY_APPLIED -> BattlePointShopPurchaseStatus.ALREADY_APPLIED
            BattlePointApplyStatus.TRANSACTION_CONFLICT -> BattlePointShopPurchaseStatus.TRANSACTION_CONFLICT
            BattlePointApplyStatus.INSUFFICIENT_FUNDS -> BattlePointShopPurchaseStatus.INSUFFICIENT_FUNDS
            BattlePointApplyStatus.COMMIT_REJECTED -> when (deliveryState) {
                DeliveryState.PREPARE_REJECTED -> BattlePointShopPurchaseStatus.INVENTORY_REJECTED
                else -> BattlePointShopPurchaseStatus.DELIVERY_FAILED
            }
            BattlePointApplyStatus.UNAVAILABLE -> BattlePointShopPurchaseStatus.BP_UNAVAILABLE
            BattlePointApplyStatus.BALANCE_OVERFLOW -> BattlePointShopPurchaseStatus.CART_OVERFLOW
        }
        return BattlePointShopPurchaseResult(status, resolved.totalCostBp, bpResult.balance)
    }

    private fun resolveCart(catalog: BattlePointShopCatalog, lines: List<BattlePointShopCartLine>): CartResolution {
        if (lines.isEmpty() || lines.size > catalog.limits.maxCartLines) {
            return CartResolution.Rejected(BattlePointShopPurchaseStatus.INVALID_CART)
        }
        val seen = HashSet<String>()
        val grants = ArrayList<BattlePointShopGrant>(lines.size)
        var totalCost = 0L
        var totalItems = 0
        for (line in lines) {
            if (!seen.add(line.entryId) || line.quantity !in 1..catalog.limits.maxQuantityPerLine) {
                return CartResolution.Rejected(BattlePointShopPurchaseStatus.INVALID_CART)
            }
            val entry = catalog.entry(line.entryId)
                ?: return CartResolution.Rejected(BattlePointShopPurchaseStatus.UNKNOWN_ENTRY)
            try {
                val itemCount = Math.multiplyExact(entry.itemCount, line.quantity)
                totalItems = Math.addExact(totalItems, itemCount)
                totalCost = Math.addExact(totalCost, Math.multiplyExact(entry.priceBp, line.quantity.toLong()))
                grants += BattlePointShopGrant(entry.itemId, itemCount)
            } catch (_: ArithmeticException) {
                return CartResolution.Rejected(BattlePointShopPurchaseStatus.CART_OVERFLOW)
            }
            if (totalItems > catalog.limits.maxTotalItems) {
                return CartResolution.Rejected(BattlePointShopPurchaseStatus.INVALID_CART)
            }
        }
        return CartResolution.Accepted(totalCost, grants.toList())
    }

    private fun purchaseReason(catalogId: String, revision: String, lines: List<BattlePointShopCartLine>): String {
        val canonical = buildString {
            append(catalogId).append(':').append(revision).append('\n')
            lines.sortedBy(BattlePointShopCartLine::entryId).forEach { line ->
                append(line.entryId).append(':').append(line.quantity).append('\n')
            }
        }
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "shop_purchase:$catalogId:$fingerprint"
    }

    private sealed interface CartResolution {
        data class Accepted(val totalCostBp: Long, val grants: List<BattlePointShopGrant>) : CartResolution
        data class Rejected(val status: BattlePointShopPurchaseStatus) : CartResolution
    }

    private enum class DeliveryState {
        NOT_ATTEMPTED,
        PREPARE_REJECTED,
        COMMITTED,
        COMMIT_FAILED,
    }

    companion object {
        val SOURCE_ID = BattlePointSourceId("cobblemon_more_battle_content:bp_shop")
    }
}

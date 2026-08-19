package jbro.cobblemon.morebattlecontent.internal.bp.shop

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointApplyStatus
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointOperation
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointRequest
import jbro.cobblemon.morebattlecontent.internal.bp.BattlePointStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattlePointShopServiceTest {
    private val playerId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val purchaseId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @Test
    fun `applies an entire cart once and identical retries do not deliver again`() {
        val store = fundedStore(100)
        val delivery = RecordingDelivery()
        val service = service(store, delivery)
        val request = request(lines = listOf(BattlePointShopCartLine("choice_band", 2)))

        val first = service.purchase(request)
        val retry = service.purchase(request)

        assertEquals(BattlePointShopPurchaseStatus.APPLIED, first.status)
        assertEquals(BattlePointShopPurchaseStatus.ALREADY_APPLIED, retry.status)
        assertEquals(50, first.totalCostBp)
        assertEquals(50, store.balance(playerId))
        assertEquals(1, delivery.prepareCalls)
        assertEquals(listOf(BattlePointShopGrant("cobblemon:choice_band", 2)), delivery.lastGrants)
    }

    @Test
    fun `same purchase id with a different equal-cost cart is a conflict without delivery`() {
        val store = fundedStore(100)
        val delivery = RecordingDelivery()
        val service = service(store, delivery)
        service.purchase(request(lines = listOf(BattlePointShopCartLine("choice_band", 1))))

        val conflict = service.purchase(request(lines = listOf(BattlePointShopCartLine("life_orb", 1))))

        assertEquals(BattlePointShopPurchaseStatus.TRANSACTION_CONFLICT, conflict.status)
        assertEquals(1, delivery.prepareCalls)
        assertEquals(75, store.balance(playerId))
    }

    @Test
    fun `rejects stale or malformed carts before touching inventory or BP`() {
        val store = fundedStore(100)
        val delivery = RecordingDelivery()
        val service = service(store, delivery)

        val stale = service.purchase(request(revision = "stale"))
        val duplicate = service.purchase(
            request(lines = listOf(BattlePointShopCartLine("choice_band", 1), BattlePointShopCartLine("choice_band", 1))),
        )
        val excessive = service.purchase(request(lines = listOf(BattlePointShopCartLine("choice_band", 5))))
        val unknown = service.purchase(request(lines = listOf(BattlePointShopCartLine("unknown", 1))))

        assertEquals(BattlePointShopPurchaseStatus.STALE_CATALOG, stale.status)
        assertEquals(BattlePointShopPurchaseStatus.INVALID_CART, duplicate.status)
        assertEquals(BattlePointShopPurchaseStatus.INVALID_CART, excessive.status)
        assertEquals(BattlePointShopPurchaseStatus.UNKNOWN_ENTRY, unknown.status)
        assertEquals(0, delivery.prepareCalls)
        assertEquals(100, store.balance(playerId))
    }

    @Test
    fun `insufficient funds does not prepare delivery`() {
        val store = fundedStore(10)
        val delivery = RecordingDelivery()

        val result = service(store, delivery).purchase(request())

        assertEquals(BattlePointShopPurchaseStatus.INSUFFICIENT_FUNDS, result.status)
        assertEquals(0, delivery.prepareCalls)
        assertEquals(10, store.balance(playerId))
    }

    @Test
    fun `failed delivery rolls back and does not debit BP`() {
        val store = fundedStore(100)
        val delivery = RecordingDelivery(commitResult = false)

        val result = service(store, delivery).purchase(request())

        assertEquals(BattlePointShopPurchaseStatus.DELIVERY_FAILED, result.status)
        assertEquals(1, delivery.rollbackCalls)
        assertEquals(100, store.balance(playerId))
        assertEquals(1, store.history(playerId, 10).size)
    }

    @Test
    fun `cost and item-count overflow are rejected`() {
        val hugeCatalog = catalog(
            limits = BattlePointShopLimits(2, Int.MAX_VALUE, Int.MAX_VALUE),
            entries = listOf(BattlePointShopEntry("huge", "cobblemon:choice_band", Int.MAX_VALUE, Long.MAX_VALUE, 10)),
        )
        val delivery = RecordingDelivery()
        val result = BattlePointShopService({ hugeCatalog }, BattlePointStore(), delivery).purchase(
            BattlePointShopPurchaseRequest(
                purchaseId,
                playerId,
                hugeCatalog.catalogId,
                hugeCatalog.revision,
                listOf(BattlePointShopCartLine("huge", 2)),
            ),
        )

        assertEquals(BattlePointShopPurchaseStatus.CART_OVERFLOW, result.status)
        assertEquals(0, delivery.prepareCalls)
    }

    private fun fundedStore(amount: Long): BattlePointStore = BattlePointStore { 1_000L }.also { store ->
        store.apply(
            BattlePointRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                playerId,
                BattlePointOperation.ContentReward(amount),
                BattlePointShopService.SOURCE_ID,
                "setup",
            ),
        )
    }

    private fun service(store: BattlePointStore, delivery: RecordingDelivery) =
        BattlePointShopService({ catalog() }, store, delivery)

    private fun request(
        revision: String = catalog().revision,
        lines: List<BattlePointShopCartLine> = listOf(BattlePointShopCartLine("choice_band", 1)),
    ) = BattlePointShopPurchaseRequest(purchaseId, playerId, "mbc_core", revision, lines)

    private fun catalog(
        limits: BattlePointShopLimits = BattlePointShopLimits(2, 4, 4),
        entries: List<BattlePointShopEntry> = listOf(
            BattlePointShopEntry("choice_band", "cobblemon:choice_band", 1, 25, 10),
            BattlePointShopEntry("life_orb", "cobblemon:life_orb", 1, 25, 20),
        ),
    ): BattlePointShopCatalog = BattlePointShopCatalog("mbc_core", "revision-1", limits, entries)

    private class RecordingDelivery(
        private val commitResult: Boolean = true,
    ) : BattlePointShopDelivery {
        var prepareCalls = 0
        var rollbackCalls = 0
        var lastGrants: List<BattlePointShopGrant> = emptyList()

        override fun prepare(playerId: UUID, grants: List<BattlePointShopGrant>): BattlePointShopDeliveryPlan? {
            prepareCalls++
            lastGrants = grants
            return object : BattlePointShopDeliveryPlan {
                override fun commit(): Boolean = commitResult
                override fun rollback() {
                    rollbackCalls++
                }
            }
        }
    }
}

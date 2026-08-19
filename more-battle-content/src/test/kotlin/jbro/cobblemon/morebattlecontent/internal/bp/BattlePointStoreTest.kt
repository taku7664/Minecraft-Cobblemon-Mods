package jbro.cobblemon.morebattlecontent.internal.bp

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BattlePointStoreTest {
    private val playerId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val otherPlayerId = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff")
    private val source = BattlePointSourceId("cobblemon_more_battle_content:battle_tower")

    @Test
    fun `credit debit and set produce an auditable balance chain`() {
        var now = 1_000L
        val store = BattlePointStore { now++ }

        val reward = store.apply(request("11111111-1111-1111-1111-111111111111", BattlePointOperation.ContentReward(30)))
        val purchase = store.apply(request("22222222-2222-2222-2222-222222222222", BattlePointOperation.ShopPurchase(12)))
        val set = store.apply(request("33333333-3333-3333-3333-333333333333", BattlePointOperation.AdminSet(50)))

        assertEquals(BattlePointApplyStatus.APPLIED, reward.status)
        assertEquals(BattlePointApplyStatus.APPLIED, purchase.status)
        assertEquals(BattlePointApplyStatus.APPLIED, set.status)
        assertEquals(50, store.balance(playerId))

        val history = store.history(playerId, 10)
        assertEquals(listOf(50L, 18L, 30L), history.map { it.balanceAfter })
        assertEquals(listOf(1_002L, 1_001L, 1_000L), history.map { it.recordedAtEpochMillis })
        assertEquals(BattlePointTransactionKind.ADMIN_SET, history.first().kind)
    }

    @Test
    fun `same player and identical transaction id returns the original result once`() {
        val store = BattlePointStore { 1_000L }
        val request = request("11111111-1111-1111-1111-111111111111", BattlePointOperation.ContentReward(20))

        val first = store.apply(request)
        val retry = store.apply(request)

        assertEquals(BattlePointApplyStatus.APPLIED, first.status)
        assertEquals(BattlePointApplyStatus.ALREADY_APPLIED, retry.status)
        assertEquals(first.transaction, retry.transaction)
        assertEquals(20, store.balance(playerId))
        assertEquals(1, store.history(playerId, 10).size)
    }

    @Test
    fun `atomic commit runs once before a purchase is recorded`() {
        val store = BattlePointStore { 1_000L }
        store.apply(request("11111111-1111-1111-1111-111111111111", BattlePointOperation.ContentReward(30)))
        val purchase = request("22222222-2222-2222-2222-222222222222", BattlePointOperation.ShopPurchase(12))
        var commitCalls = 0

        val first = store.applyAtomically(purchase) {
            commitCalls++
            assertEquals(30, store.balance(playerId))
            true
        }
        val retry = store.applyAtomically(purchase) {
            commitCalls++
            true
        }

        assertEquals(BattlePointApplyStatus.APPLIED, first.status)
        assertEquals(BattlePointApplyStatus.ALREADY_APPLIED, retry.status)
        assertEquals(1, commitCalls)
        assertEquals(18, store.balance(playerId))
        assertEquals(2, store.history(playerId, 10).size)
    }

    @Test
    fun `rejected atomic commit leaves the balance and history untouched`() {
        val store = BattlePointStore { 1_000L }
        store.apply(request("11111111-1111-1111-1111-111111111111", BattlePointOperation.ContentReward(30)))

        val rejected = store.applyAtomically(
            request("22222222-2222-2222-2222-222222222222", BattlePointOperation.ShopPurchase(12)),
        ) { false }

        assertEquals(BattlePointApplyStatus.COMMIT_REJECTED, rejected.status)
        assertEquals(30, rejected.balance)
        assertEquals(30, store.balance(playerId))
        assertEquals(1, store.history(playerId, 10).size)
    }

    @Test
    fun `atomic commit is not called for insufficient funds or a conflicting retry`() {
        val store = BattlePointStore { 1_000L }
        val transactionId = "11111111-1111-1111-1111-111111111111"
        store.apply(request(transactionId, BattlePointOperation.ContentReward(20)))
        var commitCalls = 0

        val conflict = store.applyAtomically(
            request(transactionId, BattlePointOperation.ContentReward(21)),
        ) {
            commitCalls++
            true
        }
        val insufficient = store.applyAtomically(
            request("22222222-2222-2222-2222-222222222222", BattlePointOperation.ShopPurchase(21)),
        ) {
            commitCalls++
            true
        }

        assertEquals(BattlePointApplyStatus.TRANSACTION_CONFLICT, conflict.status)
        assertEquals(BattlePointApplyStatus.INSUFFICIENT_FUNDS, insufficient.status)
        assertEquals(0, commitCalls)
        assertEquals(20, store.balance(playerId))
    }

    @Test
    fun `same player transaction id with a different payload is rejected as a conflict`() {
        val store = BattlePointStore { 1_000L }
        val transactionId = "11111111-1111-1111-1111-111111111111"
        store.apply(request(transactionId, BattlePointOperation.ContentReward(20)))

        val conflict = store.apply(request(transactionId, BattlePointOperation.ContentReward(21)))

        assertEquals(BattlePointApplyStatus.TRANSACTION_CONFLICT, conflict.status)
        assertEquals(20, conflict.balance)
        assertEquals(1, store.history(playerId, 10).size)
    }

    @Test
    fun `transaction ids are idempotent per player rather than globally`() {
        val store = BattlePointStore { 1_000L }
        val transactionId = UUID.fromString("11111111-1111-1111-1111-111111111111")

        val first = store.apply(request(transactionId.toString(), BattlePointOperation.ContentReward(15)))
        val second = store.apply(
            BattlePointRequest(
                transactionId,
                otherPlayerId,
                BattlePointOperation.ContentReward(15),
                source,
                "tower_win",
            ),
        )

        assertEquals(BattlePointApplyStatus.APPLIED, first.status)
        assertEquals(BattlePointApplyStatus.APPLIED, second.status)
        assertEquals(15, store.balance(playerId))
        assertEquals(15, store.balance(otherPlayerId))
    }

    @Test
    fun `insufficient funds and overflow leave balance and history untouched`() {
        val nearLimit = BattlePointTransaction(
            transactionId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            playerId = playerId,
            kind = BattlePointTransactionKind.ADMIN_SET,
            requestedValue = Long.MAX_VALUE,
            balanceBefore = 0,
            balanceAfter = Long.MAX_VALUE,
            sourceId = source,
            reason = "setup",
            recordedAtEpochMillis = 1L,
        )
        val store = BattlePointStore(listOf(BattlePointAccount(playerId, Long.MAX_VALUE, listOf(nearLimit)))) { 2L }

        val overflow = store.apply(request("11111111-1111-1111-1111-111111111111", BattlePointOperation.AdminAdd(1)))
        val insufficientStore = BattlePointStore { 2L }
        val insufficient = insufficientStore.apply(
            request("22222222-2222-2222-2222-222222222222", BattlePointOperation.AdminRemove(1)),
        )

        assertEquals(BattlePointApplyStatus.BALANCE_OVERFLOW, overflow.status)
        assertEquals(Long.MAX_VALUE, store.balance(playerId))
        assertEquals(1, store.history(playerId, 10).size)
        assertEquals(BattlePointApplyStatus.INSUFFICIENT_FUNDS, insufficient.status)
        assertEquals(0, insufficientStore.balance(playerId))
        assertTrue(insufficientStore.history(playerId, 10).isEmpty())
    }

    @Test
    fun `loaded account transaction chain must start at zero and end at its balance`() {
        val broken = BattlePointTransaction(
            transactionId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            playerId = playerId,
            kind = BattlePointTransactionKind.CONTENT_REWARD,
            requestedValue = 10,
            balanceBefore = 5,
            balanceAfter = 15,
            sourceId = source,
            reason = "tower_win",
            recordedAtEpochMillis = 1L,
        )

        assertThrows<IllegalArgumentException> {
            BattlePointStore(listOf(BattlePointAccount(playerId, 15, listOf(broken))))
        }
    }

    @Test
    fun `amounts source ids reasons and history limits reject invalid values`() {
        assertThrows<IllegalArgumentException> { BattlePointOperation.ContentReward(0) }
        assertThrows<IllegalArgumentException> { BattlePointOperation.AdminSet(-1) }
        assertThrows<IllegalArgumentException> { BattlePointSourceId("Admin Command") }
        assertThrows<IllegalArgumentException> {
            BattlePointRequest(UUID.randomUUID(), playerId, BattlePointOperation.AdminAdd(1), source, "")
        }
        assertThrows<IllegalArgumentException> { BattlePointStore().history(playerId, 0) }
    }

    private fun request(transactionId: String, operation: BattlePointOperation): BattlePointRequest =
        BattlePointRequest(
            UUID.fromString(transactionId),
            playerId,
            operation,
            source,
            "tower_win",
        )
}

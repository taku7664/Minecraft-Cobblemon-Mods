package jbro.cobblemon.mcc.internal.bp.recovery

import jbro.cobblemon.mcc.internal.bp.*
import jbro.cobblemon.mcc.internal.bp.shop.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class CompensationServiceTest {
    @TempDir lateinit var directory: Path
    private val player = UUID.randomUUID()
    private val id = UUID.randomUUID()
    private var store = BattlePointStore(currentTimeMillis = { 1000 })
    private var available = true
    private var rejectBp = false
    private var allowDelivery = true
    private var prepareCalls = 0
    private var stopAfterCharge = false
    private var partial = false
    private var delivered = 0
    private var lastGrants = emptyList<BattlePointShopGrant>()
    private val goods = listOf(BattlePointShopGrant("cobblemon:choice_band", 2))

    private fun request(kind: BattlePointOperation = BattlePointOperation.ContentReward(17), transaction: UUID = id) =
        BattlePointRequest(transaction, player, kind, BattlePointSourceId("more_cobblemon_contents:test"), "original entitlement")

    private fun service(apply: (BattlePointRequest) -> BattlePointApplyResult = {
        if (rejectBp) BattlePointApplyResult(BattlePointApplyStatus.UNAVAILABLE, store.balance(player)) else store.apply(it)
    }) = CompensationService(
        CompensationJournal(directory), { available }, store::transaction, apply,
        { store.history(it, 1).firstOrNull()?.transactionId },
        { store.operatorChanged(it.player, it.baseline, it.baselineKnown) },
        object : BattlePointShopDelivery {
            override fun prepare(playerId: UUID, grants: List<BattlePointShopGrant>): BattlePointShopDeliveryPlan? {
                prepareCalls++
                if (!allowDelivery || (stopAfterCharge && prepareCalls > 1)) return null
                return object : BattlePointShopDeliveryPlan {
                    override fun commit(): Boolean {
                        delivered++
                        lastGrants = grants
                        if (partial) throw IllegalStateException("partial delivery")
                        return true
                    }
                    override fun rollback() { /* Intentionally cannot prove whether a partial delivery was consumed. */ }
                }
            }
        }, { 2000 },
    )

    private fun fund() { store.apply(request(BattlePointOperation.AdminSet(100), UUID.randomUUID())) }
    private fun restart() {
        store = BattlePointStore(BattlePointNbtCodec.decode(BattlePointNbtCodec.encode(store.allAccounts())))
    }

    @Test
    fun `unpaid reward uses its exact original identity and amount across inquiries and restarts`() {
        rejectBp = true
        assertEquals(CompensationService.Status.WAITING, service().reward(request()).status)
        assertEquals(0, store.balance(player))
        restart()
        rejectBp = false
        assertEquals(CompensationService.Status.APPLIED, service().recover(player, id).status)
        assertEquals(17, store.balance(player))
        restart()
        repeat(4) {
            assertEquals(CompensationService.Status.ALREADY_APPLIED, service().recover(player, id).status)
            assertEquals(17, store.balance(player))
        }
        assertEquals(1, store.history(player, 10).size)
        assertEquals(id, store.history(player, 10).single().transactionId)
    }

    @Test
    fun `unavailable store preserves an authorized reward without claiming it was paid`() {
        available = false
        assertEquals(CompensationService.Status.WAITING, service().reward(request()).status)
        restart()
        available = true
        assertEquals(CompensationService.Status.APPLIED, service().recover(player, id).status)
        assertEquals(17, store.balance(player))
    }

    @Test
    fun `confirmed charge with delivery never started resumes using original goods without charging again`() {
        fund()
        stopAfterCharge = true
        assertEquals(CompensationService.Status.WAITING,
            service().purchase(request(BattlePointOperation.ShopPurchase(25)), goods).status)
        assertEquals(75, store.balance(player))
        assertEquals(0, delivered)
        restart()
        stopAfterCharge = false
        assertEquals(CompensationService.Status.APPLIED, service().recover(player, id).status)
        assertEquals(goods, lastGrants)
        assertEquals(75, store.balance(player))
        restart()
        repeat(3) { assertEquals(CompensationService.Status.ALREADY_APPLIED, service().recover(player, id).status) }
        assertEquals(1, delivered)
        assertEquals(75, store.balance(player))
    }

    @Test
    fun `initially full inventory never incurs a charge and full recovery remains pending`() {
        fund()
        allowDelivery = false
        assertEquals(CompensationService.Status.REJECTED,
            service().purchase(request(BattlePointOperation.ShopPurchase(25)), goods).status)
        assertEquals(100, store.balance(player))
        allowDelivery = true
        stopAfterCharge = true
        prepareCalls = 0
        service().purchase(request(BattlePointOperation.ShopPurchase(25)), goods)
        restart()
        allowDelivery = false
        assertEquals(CompensationService.Status.WAITING, service().recover(player, id).status)
        assertEquals(75, store.balance(player))
        assertEquals(0, delivered)
    }

    @Test
    fun `partial or failed delivery is permanently operator-only even after restart`() {
        fund()
        partial = true
        assertEquals(CompensationService.Status.OPERATOR,
            service().purchase(request(BattlePointOperation.ShopPurchase(25)), goods).status)
        restart()
        partial = false
        repeat(3) { assertEquals(CompensationService.Status.OPERATOR, service().recover(player, id).status) }
        assertEquals(1, delivered)
        assertEquals(75, store.balance(player))
    }

    @Test
    fun `interruption during BP mutation is ambiguous unless the matching receipt survived`() {
        class Crash : Error()
        assertThrows<Crash> { service { throw Crash() }.reward(request()) }
        restart()
        assertEquals(CompensationService.Status.OPERATOR, service().recover(player, id).status)
        assertEquals(0, store.balance(player))
        val other = UUID.randomUUID()
        assertThrows<Crash> { service { store.apply(it); throw Crash() }.reward(request(transaction = other)) }
        restart()
        assertEquals(CompensationService.Status.ALREADY_APPLIED, service().recover(player, other).status)
        assertEquals(17, store.balance(player))
    }

    @Test
    fun `delivery marker surviving a crash forbids redelivery even if no completion marker exists`() {
        fund()
        stopAfterCharge = true
        service().purchase(request(BattlePointOperation.ShopPurchase(25)), goods)
        val journal = CompensationJournal(directory)
        journal.write(journal.read(player, id)!!.copy(phase = CompensationPhase.DELIVERING))
        restart()
        stopAfterCharge = false
        assertEquals(CompensationService.Status.OPERATOR, service().recover(player, id).status)
        assertEquals(0, delivered)
    }

    @Test
    fun `manual compensation blocks both inquiry recovery and original operation replay regardless of timestamps`() {
        fund()
        rejectBp = true
        service().reward(request())
        // Store timestamps are deliberately earlier than journal time. Ordering uses the original history anchor.
        store.apply(request(BattlePointOperation.AdminAdd(17), UUID.randomUUID()))
        rejectBp = false
        restart()
        assertEquals(CompensationService.Status.OPERATOR, service().reward(request()).status)
        assertEquals(CompensationService.Status.OPERATOR, service().recover(player, id).status)
        assertEquals(117, store.balance(player))
    }

    @Test
    fun `changed amounts goods owners and missing legacy records cannot authorize compensation`() {
        rejectBp = true
        service().reward(request())
        rejectBp = false
        assertEquals(CompensationService.Status.OPERATOR, service().reward(request(BattlePointOperation.ContentReward(999))).status)
        assertEquals(CompensationService.Status.OPERATOR, service().recover(UUID.randomUUID(), id).status)
        assertEquals(CompensationService.Status.OPERATOR, service().recover(player, UUID.randomUUID()).status)
        assertEquals(0, store.balance(player))
        assertEquals(CompensationService.Status.OPERATOR, service().purchase(
            request(BattlePointOperation.ShopPurchase(17)), goods).status)
        assertEquals(0, delivered)
    }

    @Test
    fun `completed marker without its BP receipt cannot be replayed after a rollback`() {
        service().reward(request())
        store = BattlePointStore()
        assertEquals(CompensationService.Status.OPERATOR, service().recover(player, id).status)
        assertEquals(0, store.balance(player))
    }

    @Test
    fun `corrupt and deleted journals fail closed before any new mutation`() {
        rejectBp = true
        service().reward(request())
        val entry = directory.resolve(player.toString()).resolve("$id.json")
        Files.writeString(entry, "{broken")
        rejectBp = false
        assertThrows<Exception> { service().recover(player, id) }
        assertEquals(0, store.balance(player))
        Files.delete(entry)
        Files.delete(entry.parent)
        assertThrows<IllegalStateException> { service().recover(player, id) }
        assertEquals(0, store.balance(player))
    }

    @Test
    fun `unknown persisted phase cannot be treated as an unattempted delivery`() {
        fund()
        stopAfterCharge = true
        service().purchase(request(BattlePointOperation.ShopPurchase(25)), goods)
        val file = directory.resolve(player.toString()).resolve("$id.json")
        Files.writeString(file, Files.readString(file).replace("CHARGED", "UNKNOWN_PHASE"))
        stopAfterCharge = false
        assertThrows<IllegalArgumentException> { service().recover(player, id) }
        assertEquals(0, delivered)
        assertEquals(75, store.balance(player))
    }

    @Test
    fun `unwritable journal cannot execute the BP mutator`() {
        val blocked = directory.resolve("not-a-directory")
        Files.writeString(blocked, "blocked")
        directory = blocked
        assertThrows<Exception> { service().reward(request()) }
        assertEquals(0, store.balance(player))
    }

    @Test
    fun `candidate selection never guesses between transactions or crosses owners kinds and time windows`() {
        val other = UUID.randomUUID()
        val entry = CompensationEntry(player, id, BattlePointTransactionKind.CONTENT_REWARD, 17,
            "more_cobblemon_contents:test", "original", emptyList(), 2000)
        val entries = listOf(entry, entry.copy(transaction = other), entry.copy(player = UUID.randomUUID()))
        val kind = jbro.cobblemon.mcc.api.support.MccCompensation.Kind.REWARD
        fun select(owner: UUID = player, from: Long = 0, to: Long = 3000, transaction: UUID? = null) =
            jbro.cobblemon.mcc.api.support.MccCompensation.candidates(entries, owner, kind, from, to, transaction)
        assertEquals(2, select().size)
        assertEquals(listOf(entry), select(transaction = id))
        assertTrue(select(owner = UUID.randomUUID()).isEmpty())
        assertTrue(select(from = 3000, to = 4000).isEmpty())
        assertEquals(listOf(entry), select(from = 3000, to = 4000, transaction = id))
        assertTrue(jbro.cobblemon.mcc.api.support.MccCompensation.candidates(entries, player,
            jbro.cobblemon.mcc.api.support.MccCompensation.Kind.SHOP, 0, 3000, id).isEmpty())
    }

    @Test
    fun `conflicting equal-price goods do not replace the saved cart used by recovery`() {
        fund()
        stopAfterCharge = true
        val original = request(BattlePointOperation.ShopPurchase(25))
        service().purchase(original, goods)
        assertEquals(CompensationService.Status.OPERATOR,
            service().purchase(original, listOf(BattlePointShopGrant("minecraft:diamond", 64))).status)
        restart()
        stopAfterCharge = false
        assertEquals(CompensationService.Status.APPLIED, service().recover(player, id).status)
        assertEquals(goods, lastGrants)
        assertEquals(75, store.balance(player))
    }

    @Test
    fun `operator hold before a manual item grant survives restart and blocks all automatic retries`() {
        fund()
        stopAfterCharge = true
        service().purchase(request(BattlePointOperation.ShopPurchase(25)), goods)
        assertTrue(service().hold(player, id))
        assertFalse(service().hold(UUID.randomUUID(), id))
        restart()
        stopAfterCharge = false
        assertEquals(CompensationService.Status.OPERATOR, service().recover(player, id).status)
        assertEquals(CompensationService.Status.OPERATOR,
            service().purchase(request(BattlePointOperation.ShopPurchase(25)), goods).status)
        assertEquals(0, delivered)
    }

    @Test
    fun `an uncharged failed purchase cannot cause a new charge from an inquiry`() {
        fund()
        rejectBp = true
        service().purchase(request(BattlePointOperation.ShopPurchase(25)), goods)
        rejectBp = false
        restart()
        assertEquals(CompensationService.Status.OPERATOR, service().recover(player, id).status)
        assertEquals(100, store.balance(player))
        assertEquals(0, delivered)
    }
}

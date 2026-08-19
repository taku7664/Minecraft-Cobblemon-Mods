package jbro.cobblemon.morebattlecontent.internal.bp

import java.util.UUID
import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattlePointPersistenceTest {
    private val playerId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val transactionId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val source = BattlePointSourceId("cobblemon_more_battle_content:battle_tower")
    private val request = BattlePointRequest(
        transactionId,
        playerId,
        BattlePointOperation.ContentReward(25),
        source,
        "tower_win",
    )

    @Test
    fun `nbt round trip preserves balances and the full transaction ledger`() {
        val store = BattlePointStore { 123_456L }
        store.apply(request)
        store.apply(
            BattlePointRequest(
                UUID.fromString("66666666-7777-8888-9999-000000000000"),
                playerId,
                BattlePointOperation.AdminRemove(5),
                BattlePointSourceId("cobblemon_more_battle_content:admin_command"),
                "operator_adjustment",
            ),
        )

        val tag = BattlePointNbtCodec.encode(store.allAccounts())
        val restored = BattlePointStore(BattlePointNbtCodec.decode(tag))

        assertEquals(1, tag.getInt("SchemaVersion"))
        assertEquals(store.allAccounts(), restored.allAccounts())
        assertEquals(20, restored.balance(playerId))
        assertEquals(2, restored.history(playerId, 10).size)
    }

    @Test
    fun `saved data becomes dirty only for a newly applied transaction`() {
        val fresh = BattlePointSavedData(BattlePointStore(currentTimeMillis = { 1L }))
        val applied = fresh.apply(request)

        assertEquals(BattlePointApplyStatus.APPLIED, applied.status)
        assertTrue(fresh.isDirty)

        val populatedStore = BattlePointStore(currentTimeMillis = { 1L }).also { it.apply(request) }
        val duplicateData = BattlePointSavedData(populatedStore)
        val duplicate = duplicateData.apply(request)
        val failedData = BattlePointSavedData(BattlePointStore(currentTimeMillis = { 1L }))
        val failed = failedData.apply(request.copy(operation = BattlePointOperation.AdminRemove(1)))

        assertEquals(BattlePointApplyStatus.ALREADY_APPLIED, duplicate.status)
        assertFalse(duplicateData.isDirty)
        assertEquals(BattlePointApplyStatus.INSUFFICIENT_FUNDS, failed.status)
        assertFalse(failedData.isDirty)
    }

    @Test
    fun `atomic saved-data writes become dirty only after the external commit succeeds`() {
        val rejectedData = BattlePointSavedData(BattlePointStore(currentTimeMillis = { 1L }))
        val rejected = rejectedData.applyAtomically(request) { false }
        val appliedData = BattlePointSavedData(BattlePointStore(currentTimeMillis = { 1L }))
        val applied = appliedData.applyAtomically(request) { true }

        assertEquals(BattlePointApplyStatus.COMMIT_REJECTED, rejected.status)
        assertFalse(rejectedData.isDirty)
        assertEquals(BattlePointApplyStatus.APPLIED, applied.status)
        assertTrue(appliedData.isDirty)
    }

    @Test
    fun `unsupported data preserves the original nbt and disables all writes`() {
        val unsupported = CompoundTag().also { tag ->
            tag.putInt("SchemaVersion", 999)
            tag.putString("KeepMe", "original")
        }

        val data = BattlePointSavedData.loadForTest(unsupported)
        val result = data.apply(request)
        var atomicCommitCalls = 0
        val atomicResult = data.applyAtomically(request) {
            atomicCommitCalls++
            true
        }
        val saved = data.saveForTest()

        assertFalse(data.isAvailable)
        assertEquals(BattlePointApplyStatus.UNAVAILABLE, result.status)
        assertEquals(BattlePointApplyStatus.UNAVAILABLE, atomicResult.status)
        assertEquals(0, atomicCommitCalls)
        assertFalse(data.isDirty)
        assertEquals(999, saved.getInt("SchemaVersion"))
        assertEquals("original", saved.getString("KeepMe"))
    }

    @Test
    fun `corrupt transaction chains also preserve the original nbt`() {
        val corrupt = BattlePointNbtCodec.encode(
            listOf(
                BattlePointAccount(
                    playerId,
                    25,
                    listOf(
                        BattlePointTransaction(
                            transactionId,
                            playerId,
                            BattlePointTransactionKind.CONTENT_REWARD,
                            25,
                            0,
                            25,
                            source,
                            "tower_win",
                            1L,
                        ),
                    ),
                ),
            ),
        )
        corrupt.getList("Accounts", 10).getCompound(0).putLong("Balance", 99)

        val data = BattlePointSavedData.loadForTest(corrupt)

        assertFalse(data.isAvailable)
        assertEquals(99, data.saveForTest().getList("Accounts", 10).getCompound(0).getLong("Balance"))
    }

    @Test
    fun `schema one with missing required fields is corrupt rather than an empty account store`() {
        val missingAccounts = CompoundTag().also { it.putInt("SchemaVersion", 1) }

        val data = BattlePointSavedData.loadForTest(missingAccounts)

        assertFalse(data.isAvailable)
        assertFalse(data.isDirty)
        assertFalse(data.saveForTest().contains("Accounts"))
    }
}

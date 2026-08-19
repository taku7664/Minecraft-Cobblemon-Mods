package jbro.cobblemon.morebattlecontent.internal.tower

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TowerRunCoordinatorTest {
    private val playerId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val battleId = UUID.fromString("11111111-2222-3333-4444-555555555555")

    @Test
    fun `successful callback advances progress and records exactly once`() {
        val store = BattleRecordStore()
        val coordinator = coordinator(store)
        coordinator.start(playerId, TowerProgress.initial(TowerBattleFormat.SINGLE))
        coordinator.beginBattle(playerId, battleId)

        val first = coordinator.completeBattle(playerId, battleId, TowerBattleOutcome.WIN)
        val duplicate = coordinator.completeBattle(playerId, battleId, TowerBattleOutcome.WIN)

        assertTrue(first is TowerBattleCompletionResult.Completed)
        assertEquals(TowerBattleCompletionResult.NoActiveBattle, duplicate)
        assertEquals(1, coordinator.resume(playerId)?.progress?.rankPoints)
        assertEquals(1, store.all().single().totalWins)
    }

    @Test
    fun `stale callback cannot complete a newer battle`() {
        val store = BattleRecordStore()
        val coordinator = coordinator(store)
        val newerBattleId = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa")
        coordinator.start(playerId, TowerProgress.initial(TowerBattleFormat.DOUBLE))
        coordinator.beginBattle(playerId, newerBattleId)

        val stale = coordinator.completeBattle(playerId, battleId, TowerBattleOutcome.WIN)

        assertEquals(TowerBattleCompletionResult.StaleBattle(newerBattleId), stale)
        assertEquals(newerBattleId, coordinator.resume(playerId)?.activeBattle?.battleId)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `record failure keeps pending battle and prior progress for safe retry`() {
        var calls = 0
        val recordService = TowerBattleRecordService {
            calls++
            throw IllegalStateException("record unavailable")
        }
        val coordinator = TowerRunCoordinator(recordService)
        coordinator.start(playerId, TowerProgress.initial(TowerBattleFormat.SINGLE))
        coordinator.beginBattle(playerId, battleId)

        assertThrows<IllegalStateException> {
            coordinator.completeBattle(playerId, battleId, TowerBattleOutcome.WIN)
        }

        val resumed = coordinator.resume(playerId)
        assertEquals(1, calls)
        assertEquals(0, resumed?.progress?.rankPoints)
        assertEquals(battleId, resumed?.activeBattle?.battleId)
    }

    @Test
    fun `only one run and one pending battle can exist per player`() {
        val coordinator = coordinator(BattleRecordStore())

        assertTrue(
            coordinator.start(playerId, TowerProgress.initial(TowerBattleFormat.SINGLE))
                is TowerRunStartResult.Started,
        )
        assertEquals(
            TowerRunStartResult.AlreadyActive,
            coordinator.start(playerId, TowerProgress.initial(TowerBattleFormat.DOUBLE)),
        )
        assertTrue(coordinator.beginBattle(playerId, battleId) is TowerBattleStartResult.Started)
        assertEquals(
            TowerBattleStartResult.AlreadyInBattle(battleId),
            coordinator.beginBattle(playerId, UUID.randomUUID()),
        )
    }

    @Test
    fun `abandon removes only in-memory run state`() {
        val store = BattleRecordStore()
        val coordinator = coordinator(store)
        coordinator.start(playerId, TowerProgress.initial(TowerBattleFormat.SINGLE))

        assertTrue(coordinator.abandon(playerId))
        assertEquals(null, coordinator.resume(playerId))
        assertTrue(store.all().isEmpty())
    }

    private fun coordinator(store: BattleRecordStore): TowerRunCoordinator =
        TowerRunCoordinator(TowerBattleRecordService(store::recordCompletedBattle))
}

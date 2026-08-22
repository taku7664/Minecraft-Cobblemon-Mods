package jbro.cobblemon.morebattlecontent.internal.tower

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordCategory
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordKey
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TowerBattleRecordServiceTest {
    private val playerId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

    @Test
    fun `record store streak fields are the only tower progression source`() {
        val store = BattleRecordStore()
        val service = TowerBattleRecordService(store::recordCompletedBattle)
        var progress = TowerProgress.initial(TowerBattleFormat.SINGLE)

        repeat(6) {
            val update = TowerProgression.record(progress, TowerBattleOutcome.WIN)
            val stats = service.record(playerId, update)
            progress = TowerProgressRecordCodec.decode(stats)
        }

        assertEquals(6, progress.currentWinStreak)
        assertEquals(6, progress.bestWinStreak)
        assertTrue(store.all().single().progressMetrics.isEmpty())
        assertTrue(store.all().single().bestMetrics.isEmpty())
    }

    @Test
    fun `loss resets current streak and preserves best streak`() {
        val store = BattleRecordStore()
        val service = TowerBattleRecordService(store::recordCompletedBattle)
        val key = BattleRecordKey(playerId, BattleRecordCategory("battle_tower", "double"))
        var progress = TowerProgress.initial(TowerBattleFormat.DOUBLE)
        repeat(5) {
            val update = TowerProgression.record(progress, TowerBattleOutcome.WIN)
            progress = TowerProgressRecordCodec.decode(service.record(playerId, update))
        }

        val loss = service.record(playerId, TowerProgression.record(progress, TowerBattleOutcome.LOSS))

        assertEquals(0, loss.currentWinStreak)
        assertEquals(5, loss.bestWinStreak)
        assertEquals(5, store.get(key).bestWinStreak)
    }

    @Test
    fun `single and double records remain isolated`() {
        val store = BattleRecordStore()
        val service = TowerBattleRecordService(store::recordCompletedBattle)
        service.record(playerId, TowerProgression.record(TowerProgress.initial(TowerBattleFormat.SINGLE), TowerBattleOutcome.WIN))
        service.record(playerId, TowerProgression.record(TowerProgress.initial(TowerBattleFormat.DOUBLE), TowerBattleOutcome.LOSS))

        assertEquals(1, store.get(BattleRecordKey(playerId, BattleRecordCategory("battle_tower", "single"))).totalWins)
        assertEquals(1, store.get(BattleRecordKey(playerId, BattleRecordCategory("battle_tower", "double"))).totalLosses)
    }
}

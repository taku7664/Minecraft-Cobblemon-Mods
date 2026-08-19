package jbro.cobblemon.morebattlecontent.internal.tower

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordCategory
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordKey
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordMetrics
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TowerBattleRecordServiceTest {
    private val playerId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

    @Test
    fun `records outcome and approved tower progress metrics atomically`() {
        val store = BattleRecordStore()
        val service = TowerBattleRecordService(store::recordCompletedBattle)
        val before = TowerProgress.initial(TowerBattleFormat.SINGLE)
        val update = TowerProgression.record(before, TowerBattleOutcome.WIN)

        val stats = service.record(playerId, update)

        assertEquals(1, stats.totalWins)
        assertEquals(0, stats.totalLosses)
        assertEquals(1, stats.currentWinStreak)
        assertEquals(1, stats.bestWinStreak)
        assertEquals(
            mapOf(
                BattleRecordMetrics.CURRENT_RANK to 1L,
                BattleRecordMetrics.RANK_PROGRESS to 1L,
                BattleRecordMetrics.MASTER_CYCLE_WINS to 0L,
            ),
            stats.progressMetrics,
        )
        assertEquals(
            mapOf(BattleRecordMetrics.HIGHEST_RANK to 1L),
            stats.bestMetrics,
        )
    }

    @Test
    fun `tier boss win stores promoted rank and never lowers highest rank`() {
        val store = BattleRecordStore()
        val service = TowerBattleRecordService(store::recordCompletedBattle)
        val bossReady = TowerProgress(
            format = TowerBattleFormat.DOUBLE,
            rank = TowerRank.RANK_3,
            rankPoints = 1,
        )
        val promoted = TowerProgression.record(bossReady, TowerBattleOutcome.WIN)

        val afterPromotion = service.record(playerId, promoted)
        assertEquals(4, afterPromotion.progressMetrics.getValue(BattleRecordMetrics.CURRENT_RANK))
        assertEquals(0, afterPromotion.progressMetrics.getValue(BattleRecordMetrics.RANK_PROGRESS))
        assertEquals(4, afterPromotion.bestMetrics.getValue(BattleRecordMetrics.HIGHEST_RANK))

        val key = BattleRecordKey(playerId, BattleRecordCategory("battle_tower", "double"))
        store.submitBestMetric(key, BattleRecordMetrics.HIGHEST_RANK, 8)
        val loss = TowerProgression.record(promoted.after, TowerBattleOutcome.LOSS)

        val afterLoss = service.record(playerId, loss)
        assertEquals(8, afterLoss.bestMetrics.getValue(BattleRecordMetrics.HIGHEST_RANK))
        assertEquals(1, afterLoss.totalWins)
        assertEquals(1, afterLoss.totalLosses)
        assertEquals(0, afterLoss.currentWinStreak)
    }

    @Test
    fun `max boss loss persists reset cycle without storing session data`() {
        val store = BattleRecordStore()
        val service = TowerBattleRecordService(store::recordCompletedBattle)
        val bossReady = TowerProgress(
            format = TowerBattleFormat.SINGLE,
            rank = TowerRank.MAX,
            masterCycleWins = 9,
        )

        val stats = service.record(
            playerId,
            TowerProgression.record(bossReady, TowerBattleOutcome.LOSS),
        )

        assertEquals(1, stats.totalLosses)
        assertEquals(11, stats.progressMetrics.getValue(BattleRecordMetrics.CURRENT_RANK))
        assertEquals(0, stats.progressMetrics.getValue(BattleRecordMetrics.RANK_PROGRESS))
        assertEquals(0, stats.progressMetrics.getValue(BattleRecordMetrics.MASTER_CYCLE_WINS))
        assertEquals(11, stats.bestMetrics.getValue(BattleRecordMetrics.HIGHEST_RANK))
        assertEquals(
            setOf(
                BattleRecordMetrics.CURRENT_RANK,
                BattleRecordMetrics.RANK_PROGRESS,
                BattleRecordMetrics.MASTER_CYCLE_WINS,
            ),
            stats.progressMetrics.keys,
        )
    }

    @Test
    fun `single and double records remain isolated`() {
        val store = BattleRecordStore()
        val service = TowerBattleRecordService(store::recordCompletedBattle)

        service.record(
            playerId,
            TowerProgression.record(
                TowerProgress.initial(TowerBattleFormat.SINGLE),
                TowerBattleOutcome.WIN,
            ),
        )
        service.record(
            playerId,
            TowerProgression.record(
                TowerProgress.initial(TowerBattleFormat.DOUBLE),
                TowerBattleOutcome.LOSS,
            ),
        )

        val singles = store.get(BattleRecordKey(playerId, BattleRecordCategory("battle_tower", "single")))
        val doubles = store.get(BattleRecordKey(playerId, BattleRecordCategory("battle_tower", "double")))
        assertEquals(1, singles.totalWins)
        assertEquals(0, singles.totalLosses)
        assertEquals(0, doubles.totalWins)
        assertEquals(1, doubles.totalLosses)
    }
}

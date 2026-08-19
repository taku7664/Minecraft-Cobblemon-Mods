package jbro.cobblemon.morebattlecontent.internal.tower

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordCategory
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordKey
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordMetrics
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordStats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TowerProgressRecordCodecTest {
    private val playerId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

    @Test
    fun `empty tower record starts at rank one`() {
        val stats = BattleRecordStats(key(TowerBattleFormat.SINGLE))

        assertEquals(
            TowerProgress.initial(TowerBattleFormat.SINGLE),
            TowerProgressRecordCodec.decode(stats),
        )
    }

    @Test
    fun `decodes approved rank progress metrics`() {
        val stats = BattleRecordStats(
            key = key(TowerBattleFormat.DOUBLE),
            progressMetrics = mapOf(
                BattleRecordMetrics.CURRENT_RANK to 11,
                BattleRecordMetrics.RANK_PROGRESS to 0,
                BattleRecordMetrics.MASTER_CYCLE_WINS to 7,
            ),
            bestMetrics = mapOf(BattleRecordMetrics.HIGHEST_RANK to 11),
        )

        assertEquals(
            TowerProgress(
                format = TowerBattleFormat.DOUBLE,
                rank = TowerRank.MAX,
                masterCycleWins = 7,
            ),
            TowerProgressRecordCodec.decode(stats),
        )
    }

    @Test
    fun `rejects partial progress instead of guessing missing values`() {
        val stats = BattleRecordStats(
            key = key(TowerBattleFormat.SINGLE),
            progressMetrics = mapOf(BattleRecordMetrics.CURRENT_RANK to 4),
        )

        assertThrows<IllegalArgumentException> {
            TowerProgressRecordCodec.decode(stats)
        }
    }

    @Test
    fun `rejects invalid rank and progress combinations`() {
        val invalidRank = BattleRecordStats(
            key = key(TowerBattleFormat.SINGLE),
            progressMetrics = metrics(rank = 12, rankPoints = 0, masterWins = 0),
        )
        val invalidRankPoints = BattleRecordStats(
            key = key(TowerBattleFormat.SINGLE),
            progressMetrics = metrics(rank = 1, rankPoints = 2, masterWins = 0),
        )
        val nonMaxMasterWins = BattleRecordStats(
            key = key(TowerBattleFormat.SINGLE),
            progressMetrics = metrics(rank = 10, rankPoints = 0, masterWins = 1),
        )

        assertThrows<IllegalArgumentException> { TowerProgressRecordCodec.decode(invalidRank) }
        assertThrows<IllegalArgumentException> { TowerProgressRecordCodec.decode(invalidRankPoints) }
        assertThrows<IllegalArgumentException> { TowerProgressRecordCodec.decode(nonMaxMasterWins) }
    }

    @Test
    fun `rejects records outside tower formats`() {
        val stats = BattleRecordStats(
            BattleRecordKey(playerId, BattleRecordCategory("battle_factory", "single")),
        )

        assertThrows<IllegalArgumentException> {
            TowerProgressRecordCodec.decode(stats)
        }
    }

    private fun key(format: TowerBattleFormat) = BattleRecordKey(
        playerId = playerId,
        category = BattleRecordCategory("battle_tower", format.recordId),
    )

    private fun metrics(rank: Long, rankPoints: Long, masterWins: Long) = mapOf(
        BattleRecordMetrics.CURRENT_RANK to rank,
        BattleRecordMetrics.RANK_PROGRESS to rankPoints,
        BattleRecordMetrics.MASTER_CYCLE_WINS to masterWins,
    )
}

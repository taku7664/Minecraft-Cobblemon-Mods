package jbro.cobblemon.morebattlecontent.internal.tower

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordCategory
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordKey
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordMetricId
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordStats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TowerProgressRecordCodecTest {
    private val playerId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

    @Test
    fun `decodes current and best streak without consulting unrelated progress metrics`() {
        val stats = BattleRecordStats(
            key = key(TowerBattleFormat.DOUBLE),
            currentWinStreak = 14,
            bestWinStreak = 27,
            progressMetrics = mapOf(BattleRecordMetricId("unrelated_progress") to 11),
        )

        assertEquals(TowerProgress(TowerBattleFormat.DOUBLE, 14, 27), TowerProgressRecordCodec.decode(stats))
    }

    @Test
    fun `empty tower record starts at zero streak`() {
        assertEquals(
            TowerProgress.initial(TowerBattleFormat.SINGLE),
            TowerProgressRecordCodec.decode(BattleRecordStats(key(TowerBattleFormat.SINGLE))),
        )
    }

    @Test
    fun `rejects records outside tower formats`() {
        val stats = BattleRecordStats(BattleRecordKey(playerId, BattleRecordCategory("battle_factory", "single")))
        assertThrows<IllegalArgumentException> { TowerProgressRecordCodec.decode(stats) }
    }

    private fun key(format: TowerBattleFormat) = BattleRecordKey(
        playerId,
        BattleRecordCategory("battle_tower", format.recordId),
    )
}

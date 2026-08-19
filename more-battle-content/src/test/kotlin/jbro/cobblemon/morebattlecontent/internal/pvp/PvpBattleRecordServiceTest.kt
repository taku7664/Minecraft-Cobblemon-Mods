package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordCategory
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordKey
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordStats
import jbro.cobblemon.morebattlecontent.internal.record.BattleRecordStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PvpBattleRecordServiceTest {
    private val winner = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val loser = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

    @Test
    fun `one match completion records winner and loser in the same format`() {
        val store = BattleRecordStore()
        val service = PvpBattleRecordService(store::recordCompletedBattles)

        val result = service.recordResult(winner, loser, PvpBattleFormat.DOUBLE)

        assertEquals(1, result.getValue(winner).totalWins)
        assertEquals(0, result.getValue(winner).totalLosses)
        assertEquals(0, result.getValue(loser).totalWins)
        assertEquals(1, result.getValue(loser).totalLosses)
        assertEquals("pvp", result.getValue(winner).key.category.contentId)
        assertEquals("double", result.getValue(winner).key.category.formatId)
    }

    @Test
    fun `pair update is atomic when either participant counter overflows`() {
        val category = BattleRecordCategory("pvp", "single")
        val winnerKey = BattleRecordKey(winner, category)
        val loserKey = BattleRecordKey(loser, category)
        val winnerBefore = BattleRecordStats(
            key = winnerKey,
            totalWins = Long.MAX_VALUE,
            currentWinStreak = 1,
            bestWinStreak = 1,
        )
        val loserBefore = BattleRecordStats(loserKey)
        val store = BattleRecordStore(listOf(winnerBefore, loserBefore))
        val service = PvpBattleRecordService(store::recordCompletedBattles)

        assertThrows<ArithmeticException> { service.recordResult(winner, loser, PvpBattleFormat.SINGLE) }

        assertEquals(winnerBefore, store.get(winnerKey))
        assertEquals(loserBefore, store.get(loserKey))
    }

    @Test
    fun `a player cannot be both winner and loser`() {
        val service = PvpBattleRecordService(BattleRecordStore()::recordCompletedBattles)

        assertThrows<IllegalArgumentException> { service.recordResult(winner, winner, PvpBattleFormat.SINGLE) }
    }
}

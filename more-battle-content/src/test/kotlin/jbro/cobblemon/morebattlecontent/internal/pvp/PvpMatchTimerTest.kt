package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpMatchTimerTest {
    private val first = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val second = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

    @Test
    fun `entry selection accepts before ninety seconds and forfeits at the deadline`() {
        var now = 1_000L
        val timer = PvpMatchTimer(setOf(first, second), PvpRulesPreset.champions(), timeSource { now })
        timer.beginEntrySelection()

        now += 89_999L
        assertEquals(PvpTimedSubmissionStatus.ACCEPTED, timer.submitEntrySelection(first))
        now += 1L
        assertEquals(PvpTimedSubmissionStatus.TIMED_OUT, timer.submitEntrySelection(second))
        assertEquals(setOf(second), timer.entrySelectionTimeouts())
    }

    @Test
    fun `turn deadline forfeits only players who still owe a choice`() {
        var now = 0L
        val timer = PvpMatchTimer(setOf(first, second), PvpRulesPreset.champions(), timeSource { now })
        timer.beginTurn(1, setOf(first, second))

        now = 30_000L
        assertEquals(PvpTimedSubmissionStatus.ACCEPTED, timer.submitTurn(1, first))
        now = 45_000L

        assertEquals(setOf(second), timer.turnTimeouts(1))
        assertEquals(390_000L, timer.remainingPersonalTime(first))
        assertEquals(375_000L, timer.remainingPersonalTime(second))
    }

    @Test
    fun `personal clock carries across turns and can expire before the turn limit`() {
        var now = 0L
        val rules = PvpRulesPreset.champions(turnSelectionSeconds = 45, totalBattleSecondsPerPlayer = 60)
        val timer = PvpMatchTimer(setOf(first, second), rules, timeSource { now })
        timer.beginTurn(1, setOf(first))
        now = 30_000L
        assertEquals(PvpTimedSubmissionStatus.ACCEPTED, timer.submitTurn(1, first))

        timer.beginTurn(2, setOf(first))
        now = 60_000L

        assertEquals(PvpTimedSubmissionStatus.TIMED_OUT, timer.submitTurn(2, first))
        assertEquals(0L, timer.remainingPersonalTime(first))
    }

    @Test
    fun `duplicate and stale turn submissions never consume time twice`() {
        var now = 0L
        val timer = PvpMatchTimer(setOf(first, second), PvpRulesPreset.champions(), timeSource { now })
        timer.beginTurn(7, setOf(first))
        now = 10_000L
        assertEquals(PvpTimedSubmissionStatus.ACCEPTED, timer.submitTurn(7, first))
        val remaining = timer.remainingPersonalTime(first)
        now = 20_000L

        assertEquals(PvpTimedSubmissionStatus.ALREADY_SUBMITTED, timer.submitTurn(7, first))
        assertEquals(PvpTimedSubmissionStatus.STALE_TURN, timer.submitTurn(6, first))
        assertEquals(remaining, timer.remainingPersonalTime(first))
        assertTrue(timer.turnTimeouts(7).isEmpty())
    }

    @Test
    fun `wall clock corrections do not change entry or turn elapsed time`() {
        val time = MutablePvpTimeSource(epochMillis = 10_000L, monotonicMillis = 0L)
        val timer = PvpMatchTimer(setOf(first, second), PvpRulesPreset.champions(), time)
        timer.beginEntrySelection()

        time.epochMillis += 3_600_000L
        time.monotonicMillis = 89_999L
        assertEquals(PvpTimedSubmissionStatus.ACCEPTED, timer.submitEntrySelection(first))
        assertEquals(100_000L, timer.entryDeadlineMillis())

        time.epochMillis = 1_000L
        time.monotonicMillis = 90_000L
        assertEquals(PvpTimedSubmissionStatus.TIMED_OUT, timer.submitEntrySelection(second))

        timer.beginTurn(1, setOf(first))
        time.epochMillis = 0L
        time.monotonicMillis += 30_000L
        assertEquals(PvpTimedSubmissionStatus.ACCEPTED, timer.submitTurn(1, first))
        assertEquals(390_000L, timer.remainingPersonalTime(first))
    }

    private class MutablePvpTimeSource(
        var epochMillis: Long,
        var monotonicMillis: Long,
    ) : PvpTimeSource {
        override fun epochMillis(): Long = epochMillis

        override fun monotonicMillis(): Long = monotonicMillis
    }

    private fun timeSource(now: () -> Long) = object : PvpTimeSource {
        override fun epochMillis(): Long = now()

        override fun monotonicMillis(): Long = now()
    }
}

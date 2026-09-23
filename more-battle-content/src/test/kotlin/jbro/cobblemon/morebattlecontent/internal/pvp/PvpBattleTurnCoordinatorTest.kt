package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpBattleTurnCoordinatorTest {
    private val battleId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val first = UUID.fromString("20000000-0000-0000-0000-000000000001")
    private val second = UUID.fromString("20000000-0000-0000-0000-000000000002")

    @Test
    fun `response count must exactly cover every requested slot`() {
        assertTrue(PvpTurnResponseCardinality.accepts(activeChoices = 1, forcedSwitchChoices = 1, responseCount = 1))
        assertTrue(PvpTurnResponseCardinality.accepts(activeChoices = 2, forcedSwitchChoices = 0, responseCount = 2))
        assertFalse(PvpTurnResponseCardinality.accepts(activeChoices = 2, forcedSwitchChoices = 0, responseCount = 1))
        assertFalse(PvpTurnResponseCardinality.accepts(activeChoices = 2, forcedSwitchChoices = 0, responseCount = 3))
        assertFalse(PvpTurnResponseCardinality.accepts(activeChoices = 1, forcedSwitchChoices = 2, responseCount = 1))
    }

    @Test
    fun `staggered requests receive independent turn deadlines`() {
        var now = 0L
        val timer = timer { now }
        val coordinator = PvpBattleTurnCoordinator { id -> timer.takeIf { id == battleId } }
        val firstRequest = Any()
        val secondRequest = Any()

        coordinator.observe(battleId, mapOf(first to firstRequest))
        now = 20_000L
        coordinator.observe(battleId, mapOf(first to firstRequest, second to secondRequest))
        now = 46_000L

        assertEquals(setOf(first), coordinator.timeouts().map { it.playerId }.toSet())
        assertNotNull(coordinator.capture(battleId, second, secondRequest))
    }

    @Test
    fun `invalid on-time submission can be rejected without stopping its clock`() {
        var now = 0L
        val timer = timer { now }
        val coordinator = PvpBattleTurnCoordinator { timer }
        val request = Any()
        coordinator.observe(battleId, mapOf(first to request))

        now = 10_000L
        val invalid = requireNotNull(coordinator.capture(battleId, first, request))
        assertFalse(invalid.timedOut)
        coordinator.reject(invalid)
        now = 46_000L

        assertEquals(listOf(first), coordinator.timeouts().map { it.playerId })
    }

    @Test
    fun `late packet resolves as timeout while on-time packet is accepted`() {
        var now = 0L
        val timer = timer { now }
        val coordinator = PvpBattleTurnCoordinator { timer }
        val firstRequest = Any()
        val secondRequest = Any()
        coordinator.observe(battleId, mapOf(first to firstRequest, second to secondRequest))

        now = 44_999L
        val onTime = requireNotNull(coordinator.capture(battleId, first, firstRequest))
        now = 45_000L
        val late = requireNotNull(coordinator.capture(battleId, second, secondRequest))

        assertFalse(onTime.timedOut)
        assertTrue(late.timedOut)
        assertEquals(PvpTimedSubmissionStatus.ACCEPTED, coordinator.accept(onTime))
        assertEquals(PvpTimedSubmissionStatus.TIMED_OUT, coordinator.accept(late))
        val timeout = coordinator.timeouts().single()
        assertEquals(second, timeout.playerId)
        assertTrue(coordinator.acknowledgeTimeout(late))
        assertTrue(coordinator.timeouts().isEmpty())
    }

    @Test
    fun `timeout remains pending until its action is acknowledged`() {
        var now = 0L
        val timer = timer { now }
        val coordinator = PvpBattleTurnCoordinator { timer }
        val request = Any()
        coordinator.observe(battleId, mapOf(first to request))
        now = 45_000L

        val firstDelivery = coordinator.timeouts().single()
        val retryDelivery = coordinator.timeouts().single()

        assertSame(firstDelivery, retryDelivery)
        assertTrue(coordinator.acknowledgeTimeout(firstDelivery))
        assertTrue(coordinator.timeouts().isEmpty())
    }

    @Test
    fun `resolved request identity must change before a new clock starts`() {
        var now = 0L
        val timer = timer { now }
        val coordinator = PvpBattleTurnCoordinator { timer }
        val firstRequest = Any()
        coordinator.observe(battleId, mapOf(first to firstRequest))
        val capture = requireNotNull(coordinator.capture(battleId, first, firstRequest))
        assertEquals(PvpTimedSubmissionStatus.ACCEPTED, coordinator.accept(capture))

        coordinator.observe(battleId, mapOf(first to firstRequest))
        assertNull(coordinator.capture(battleId, first, firstRequest))

        val nextRequest = Any()
        coordinator.observe(battleId, mapOf(first to nextRequest))
        assertNotNull(coordinator.capture(battleId, first, nextRequest))
    }

    private fun timer(monotonic: () -> Long): PvpMatchTimer = PvpMatchTimer(
        setOf(first, second),
        PvpRulesPreset.champions(),
        object : PvpTimeSource {
            override fun epochMillis(): Long = 1_000L
            override fun monotonicMillis(): Long = monotonic()
        },
    )
}

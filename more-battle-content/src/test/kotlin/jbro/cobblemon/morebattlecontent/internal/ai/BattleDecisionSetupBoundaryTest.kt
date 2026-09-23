package jbro.cobblemon.morebattlecontent.internal.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattleDecisionSetupBoundaryTest {
    @Test
    fun `successful preparation returns its decision setup without recovery`() {
        var recovered = false

        val result = attemptBattleDecisionSetup(setup = { "ready" }, recover = { recovered = true })

        assertEquals("ready", result)
        assertEquals(false, recovered)
    }

    @Test
    fun `runtime preparation failure enters fallback and returns no pending decision`() {
        val failure = IllegalStateException("public observation failed")
        var recovered: Throwable? = null

        val result = attemptBattleDecisionSetup<String>(setup = { throw failure }, recover = { recovered = it })

        assertNull(result)
        assertSame(failure, recovered)
    }

    @Test
    fun `compatibility preparation failure enters fallback and returns no pending decision`() {
        val failure = NoSuchMethodError("battle API drift")
        var recovered: Throwable? = null

        val result = attemptBattleDecisionSetup<String>(setup = { throw failure }, recover = { recovered = it })

        assertNull(result)
        assertSame(failure, recovered)
    }

    @Test
    fun `runtime completion failure enters its recovery path`() {
        val failure = IllegalStateException("candidate lookup failed")
        var recovered: Throwable? = null

        val completed = attemptBattleDecisionCompletion(
            complete = { throw failure },
            recover = { recovered = it },
        )

        assertEquals(false, completed)
        assertSame(failure, recovered)
    }

    @Test
    fun `compatibility completion failure enters its recovery path`() {
        val failure = NoSuchMethodError("response adapter drift")
        var recovered: Throwable? = null

        val completed = attemptBattleDecisionCompletion(
            complete = { throw failure },
            recover = { recovered = it },
        )

        assertEquals(false, completed)
        assertSame(failure, recovered)
    }

    @Test
    fun `successful completion does not invoke recovery`() {
        var recovered = false

        val completed = attemptBattleDecisionCompletion(
            complete = {},
            recover = { recovered = true },
        )

        assertEquals(true, completed)
        assertEquals(false, recovered)
    }

    @Test
    fun `failed preferred fallback uses the last resort response`() {
        val failure = IllegalStateException("candidate response failed")
        val reported = mutableListOf<Throwable>()

        val response = prepareBattleDecisionFallback(
            preferred = { throw failure },
            lastResort = { "pass" },
            report = reported::add,
        )

        assertEquals("pass", response)
        assertEquals(listOf(failure), reported)
    }

    @Test
    fun `double fallback failure preserves both causes and returns no response`() {
        val preferredFailure = IllegalStateException("candidate response failed")
        val lastResortFailure = NoSuchMethodError("pass response API drift")
        val reported = mutableListOf<Throwable>()

        val response = prepareBattleDecisionFallback<String>(
            preferred = { throw preferredFailure },
            lastResort = { throw lastResortFailure },
            report = reported::add,
        )

        assertNull(response)
        assertEquals(listOf(preferredFailure), reported)
        assertEquals(listOf(lastResortFailure), preferredFailure.suppressed.toList())
        assertTrue(preferredFailure !== lastResortFailure)
    }

    @Test
    fun `diagnostics failure cannot replace a successful last resort response`() {
        val preferredFailure = IllegalStateException("candidate response failed")

        val response = prepareBattleDecisionFallback(
            preferred = { throw preferredFailure },
            lastResort = { "pass" },
            report = { throw NoSuchMethodError("logger API drift") },
        )

        assertEquals("pass", response)
        assertEquals(1, preferredFailure.suppressed.size)
    }
}

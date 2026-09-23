package jbro.cobblemon.morebattlecontent.internal.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
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
}

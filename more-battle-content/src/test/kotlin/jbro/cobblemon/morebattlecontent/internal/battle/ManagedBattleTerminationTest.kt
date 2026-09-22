package jbro.cobblemon.morebattlecontent.internal.battle

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ManagedBattleTerminationTest {
    private val battleId = UUID.randomUUID()

    @Test
    fun `settles before invoking reentrant termination`() {
        val lifecycle = ArrayList<String>()

        settleBeforeTerminatingBattle(
            battleId,
            settle = { lifecycle += "settle-$it" },
            terminate = { lifecycle += "terminate-$it" },
        )

        assertEquals(listOf("settle-$battleId", "terminate-$battleId"), lifecycle)
    }

    @Test
    fun `termination still runs and preserves both failures when settlement fails`() {
        val settlementFailure = IllegalStateException("record unavailable")
        val terminationFailure = IllegalArgumentException("termination failed")

        val thrown = assertThrows(IllegalStateException::class.java) {
            settleBeforeTerminatingBattle(
                battleId,
                settle = { throw settlementFailure },
                terminate = { throw terminationFailure },
            )
        }

        assertSame(settlementFailure, thrown)
        assertEquals(listOf(terminationFailure), thrown.suppressed.toList())
    }

    @Test
    fun `fatal settlement failure is not intercepted to run termination`() {
        val fatal = AssertionError("fatal settlement")
        var terminated = false

        val thrown = assertThrows(AssertionError::class.java) {
            settleBeforeTerminatingBattle(
                battleId,
                settle = { throw fatal },
                terminate = { terminated = true },
            )
        }

        assertSame(fatal, thrown)
        assertEquals(false, terminated)
    }

    @Test
    fun `fatal termination failure is not hidden behind ordinary settlement failure`() {
        val fatal = AssertionError("fatal termination")

        val thrown = assertThrows(AssertionError::class.java) {
            settleBeforeTerminatingBattle(
                battleId,
                settle = { throw IllegalStateException("record unavailable") },
                terminate = { throw fatal },
            )
        }

        assertSame(fatal, thrown)
    }
}

package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ManagedBattleTerminationRecoveryTest {
    @Test
    fun `successful end does not force close`() {
        val events = ArrayList<String>()

        terminateManagedBattle(
            end = { events += "end" },
            remainsRegistered = { false },
            forceClose = { events += "force-close" },
        )

        assertEquals(listOf("end"), events)
    }

    @Test
    fun `failed end force closes a battle that remains registered`() {
        val failure = IllegalStateException("end failed")
        val events = ArrayList<String>()

        val thrown = assertThrows(IllegalStateException::class.java) {
            terminateManagedBattle(
                end = {
                    events += "end"
                    throw failure
                },
                remainsRegistered = {
                    events += "registered"
                    true
                },
                forceClose = { events += "force-close" },
            )
        }

        assertSame(failure, thrown)
        assertEquals(listOf("end", "registered", "force-close"), events)
    }

    @Test
    fun `failed end does not close a battle already removed by the failing path`() {
        val failure = NoSuchMethodError("compatibility drift")
        val events = ArrayList<String>()

        val thrown = assertThrows(NoSuchMethodError::class.java) {
            terminateManagedBattle(
                end = {
                    events += "end"
                    throw failure
                },
                remainsRegistered = {
                    events += "registered"
                    false
                },
                forceClose = { events += "force-close" },
            )
        }

        assertSame(failure, thrown)
        assertEquals(listOf("end", "registered"), events)
    }

    @Test
    fun `force close failure is suppressed on the original end failure`() {
        val endFailure = IllegalStateException("end failed")
        val closeFailure = NoSuchMethodError("close failed")

        val thrown = assertThrows(IllegalStateException::class.java) {
            terminateManagedBattle(
                end = { throw endFailure },
                remainsRegistered = { true },
                forceClose = { throw closeFailure },
            )
        }

        assertSame(endFailure, thrown)
        assertEquals(listOf(closeFailure), thrown.suppressed.toList())
    }
}

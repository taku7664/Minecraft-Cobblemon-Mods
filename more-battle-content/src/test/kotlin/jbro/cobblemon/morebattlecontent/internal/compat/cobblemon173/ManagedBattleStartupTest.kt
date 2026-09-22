package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ManagedBattleStartupTest {
    @Test
    fun `post-start setup failure releases registration and terminates battle`() {
        val events = ArrayList<String>()
        val failure = IllegalStateException("attach failed")

        val thrown = assertThrows(IllegalStateException::class.java) {
            protectManagedBattleStartup(
                releasePendingRegistration = { events += "release" },
                terminateBattle = { events += "terminate" },
            ) {
                events += "setup"
                throw failure
            }
        }

        assertSame(failure, thrown)
        assertEquals(listOf("setup", "terminate", "release"), events)
    }

    @Test
    fun `cleanup failures are suppressed without skipping battle termination`() {
        val setupFailure = IllegalStateException("setup")
        val releaseFailure = IllegalArgumentException("release")
        val terminationFailure = UnsupportedOperationException("terminate")

        val thrown = assertThrows(IllegalStateException::class.java) {
            protectManagedBattleStartup(
                releasePendingRegistration = { throw releaseFailure },
                terminateBattle = { throw terminationFailure },
            ) {
                throw setupFailure
            }
        }

        assertSame(setupFailure, thrown)
        assertEquals(listOf(terminationFailure, releaseFailure), thrown.suppressed.toList())
    }

    @Test
    fun `successful setup does not release registration or terminate battle`() {
        val events = ArrayList<String>()

        val result = protectManagedBattleStartup(
            releasePendingRegistration = { events += "release" },
            terminateBattle = { events += "terminate" },
        ) {
            events += "setup"
            "started"
        }

        assertEquals("started", result)
        assertEquals(listOf("setup"), events)
    }

    @Test
    fun `linkage failure after battle creation receives the same cleanup`() {
        val events = ArrayList<String>()
        val failure = NoSuchMethodError("compatibility drift")

        val thrown = assertThrows(NoSuchMethodError::class.java) {
            protectManagedBattleStartup(
                releasePendingRegistration = { events += "release" },
                terminateBattle = { events += "terminate" },
            ) {
                throw failure
            }
        }

        assertSame(failure, thrown)
        assertEquals(listOf("terminate", "release"), events)
    }

    @Test
    fun `cleanup sequence attempts every action and preserves every failure`() {
        val first = IllegalStateException("first")
        val second = NoSuchMethodError("second")
        val events = ArrayList<String>()

        val thrown = assertThrows(IllegalStateException::class.java) {
            runManagedCleanupActions(
                {
                    events += "first"
                    throw first
                },
                { events += "middle" },
                {
                    events += "last"
                    throw second
                },
            )
        }

        assertSame(first, thrown)
        assertEquals(listOf(second), thrown.suppressed.toList())
        assertEquals(listOf("first", "middle", "last"), events)
    }

    @Test
    fun `same cleanup failure instance cannot stop later cleanup`() {
        val repeated = IllegalStateException("repeated")
        val events = ArrayList<String>()

        val thrown = assertThrows(IllegalStateException::class.java) {
            runManagedCleanupActions(
                { throw repeated },
                { throw repeated },
                { events += "last" },
            )
        }

        assertSame(repeated, thrown)
        assertEquals(emptyList<Throwable>(), thrown.suppressed.toList())
        assertEquals(listOf("last"), events)
    }
}

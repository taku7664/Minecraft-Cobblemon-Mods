package jbro.cobblemon.morebattlecontent.client

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class OptionalClientEffectTest {
    @Test
    fun `runtime and linkage failures recover and report by identity`() {
        listOf<Throwable>(IllegalStateException("render failed"), NoSuchMethodError("render API drift")).forEach { failure ->
            val calls = mutableListOf<String>()
            var reported: Throwable? = null

            assertDoesNotThrow {
                runOptionalClientEffect(
                    action = { throw failure },
                    recover = { calls += "recover" },
                    reportFailure = {
                        calls += "report"
                        reported = it
                    },
                )
            }

            assertEquals(listOf("recover", "report"), calls)
            assertSame(failure, reported)
        }
    }

    @Test
    fun `broken recovery cannot prevent reporting or escape`() {
        var reported = false

        assertDoesNotThrow {
            runOptionalClientEffect(
                action = { throw NoClassDefFoundError("renderer missing") },
                recover = { throw IllegalStateException("recovery failed") },
                reportFailure = { reported = true },
            )
        }

        assertEquals(true, reported)
    }

    @Test
    fun `fatal errors propagate`() {
        val fatal = AssertionError("fatal")

        assertSame(
            fatal,
            assertThrows(AssertionError::class.java) {
                runOptionalClientEffect(action = { throw fatal }, reportFailure = {})
            },
        )
    }

    @Test
    fun `resource release continues after ordinary and linkage failures`() {
        val released = mutableListOf<Int>()
        val failures = mutableListOf<Throwable>()

        releaseOptionalClientResourcesSafely(
            resources = listOf(1, 2, 3, 4),
            release = { resource ->
                released += resource
                when (resource) {
                    1 -> throw IllegalStateException("first failed")
                    3 -> throw NoSuchMethodError("third API drift")
                }
            },
            reportFailure = failures::add,
        )

        assertEquals(listOf(1, 2, 3, 4), released)
        assertEquals(listOf("first failed", "third API drift"), failures.map { it.message })
    }
}

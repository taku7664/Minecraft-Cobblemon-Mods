package jbro.cobblemon.morebattlecontent.internal.factory

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FactoryObservationAssemblyTest {
    @Test
    fun `runtime and linkage failures fall back to empty observations`() {
        listOf<Throwable>(IllegalStateException("mapping failed"), NoSuchMethodError("observer API drift")).forEach { failure ->
            var reported: Throwable? = null

            val observations = assembleFactoryObservationsOrEmpty<String, Int>(
                assemble = { throw failure },
                reportFailure = { reported = it },
            )

            assertEquals(emptyMap<String, Int>(), observations)
            assertSame(failure, reported)
        }
    }

    @Test
    fun `broken reporting cannot escape settlement`() {
        assertDoesNotThrow {
            assembleFactoryObservationsOrEmpty<String, Int>(
                assemble = { throw NoClassDefFoundError("observer missing") },
                reportFailure = { throw IllegalStateException("logger failed") },
            )
        }
    }

    @Test
    fun `fatal failures still propagate`() {
        val fatal = AssertionError("fatal")

        assertSame(
            fatal,
            assertThrows(AssertionError::class.java) {
                assembleFactoryObservationsOrEmpty<String, Int>(
                    assemble = { throw fatal },
                    reportFailure = {},
                )
            },
        )
    }
}

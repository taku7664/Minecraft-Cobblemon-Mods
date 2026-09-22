package jbro.cobblemon.morebattlecontent.internal.presentation

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class OptionalProjectionSendTest {
    @Test
    fun `runtime and linkage failures are reported without escaping`() {
        listOf<Throwable>(IllegalStateException("send failed"), NoSuchMethodError("network API drift")).forEach { failure ->
            var reported: Throwable? = null

            assertDoesNotThrow {
                runOptionalProjectionSend(
                    action = { throw failure },
                    reportFailure = { reported = it },
                )
            }

            assertSame(failure, reported)
        }
    }

    @Test
    fun `reporter failures cannot replace a contained send failure`() {
        assertDoesNotThrow {
            runOptionalProjectionSend(
                action = { throw NoClassDefFoundError("optional payload missing") },
                reportFailure = { throw IllegalStateException("logger failed") },
            )
        }
    }

    @Test
    fun `fatal errors still propagate`() {
        val fatal = object : Error("fatal") {}

        assertSame(
            fatal,
            assertThrows(Error::class.java) {
                runOptionalProjectionSend(
                    action = { throw fatal },
                    reportFailure = {},
                )
            },
        )
    }
}

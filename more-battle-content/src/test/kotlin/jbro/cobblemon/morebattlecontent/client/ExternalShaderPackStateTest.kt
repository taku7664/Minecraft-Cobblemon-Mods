package jbro.cobblemon.morebattlecontent.client

import java.lang.reflect.InvocationTargetException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ExternalShaderPackStateTest {
    @Test
    fun `optional integration failures fail closed`() {
        assertNull(optionalClientIntegrationCall<Boolean> { error("optional runtime failure") })
        assertNull(optionalClientIntegrationCall<Boolean> { throw NoSuchMethodError("optional API drift") })
        assertNull(
            optionalClientIntegrationCall<Boolean> {
                throw InvocationTargetException(IllegalStateException("optional target failure"))
            },
        )
    }

    @Test
    fun `fatal reflected target escapes its invocation wrapper`() {
        val fatal = AssertionError("fatal target failure")

        val thrown = assertThrows(AssertionError::class.java) {
            optionalClientIntegrationCall<Boolean> { throw InvocationTargetException(fatal) }
        }

        assertSame(fatal, thrown)
    }

    @Test
    fun `successful optional integration value is preserved`() {
        assertEquals(true, optionalClientIntegrationCall { true })
    }
}

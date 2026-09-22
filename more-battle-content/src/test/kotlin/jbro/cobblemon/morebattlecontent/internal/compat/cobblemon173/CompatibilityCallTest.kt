package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import java.io.IOException
import java.lang.reflect.InvocationTargetException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CompatibilityCallTest {
    @Test
    fun `ordinary reflection and linkage failures become unavailable values`() {
        assertNull(compatibilityCallOrNull<Boolean> { throw IOException("read failed") })
        assertNull(compatibilityCallOrNull<Boolean> { throw NoSuchMethodError("API drift") })
        assertNull(
            compatibilityCallOrNull<Boolean> {
                throw InvocationTargetException(IllegalStateException("target failed"))
            },
        )
    }

    @Test
    fun `malformed public observations can use the compatibility boundary without escaping`() {
        var laterObservationConsumed = false

        listOf<() -> Unit>(
            { throw IllegalArgumentException("malformed line") },
            { throw NoSuchMethodError("message API drift") },
            { laterObservationConsumed = true },
        ).forEach { observation ->
            compatibilityCallOrNull(observation)
        }

        assertEquals(true, laterObservationConsumed)
    }

    @Test
    fun `fatal reflected target is rethrown without its wrapper`() {
        val fatal = AssertionError("fatal target")

        val thrown = assertThrows(AssertionError::class.java) {
            compatibilityCallOrNull<Boolean> { throw InvocationTargetException(fatal) }
        }

        assertSame(fatal, thrown)
    }

    @Test
    fun `successful compatibility value is preserved`() {
        assertEquals("available", compatibilityCallOrNull { "available" })
    }

    @Test
    fun `fallback receives ordinary and linkage failures by identity`() {
        val ordinary = IOException("read failed")
        val linkage = NoSuchMethodError("API drift")

        assertSame(ordinary, compatibilityCallOrElse({ it }) { throw ordinary })
        assertSame(linkage, compatibilityCallOrElse({ it }) { throw linkage })
    }

    @Test
    fun `fallback receives an unwrapped reflective compatibility failure`() {
        val linkage = NoSuchMethodError("reflected API drift")

        assertSame(
            linkage,
            compatibilityCallOrElse({ it }) { throw InvocationTargetException(linkage) },
        )
    }

    @Test
    fun `fallback cannot hide a non-linkage fatal error`() {
        val fatal = AssertionError("fatal")
        var fallbackCalled = false

        val thrown = assertThrows(AssertionError::class.java) {
            compatibilityCallOrElse(
                fallback = {
                    fallbackCalled = true
                    it
                },
                action = { throw fatal },
            )
        }

        assertSame(fatal, thrown)
        assertEquals(false, fallbackCalled)
    }
}

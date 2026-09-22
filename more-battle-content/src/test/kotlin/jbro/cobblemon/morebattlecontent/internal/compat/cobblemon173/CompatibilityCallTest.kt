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
}

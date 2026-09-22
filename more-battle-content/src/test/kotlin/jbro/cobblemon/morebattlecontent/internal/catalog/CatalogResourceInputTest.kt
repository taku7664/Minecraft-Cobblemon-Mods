package jbro.cobblemon.morebattlecontent.internal.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CatalogResourceInputTest {
    @Test
    fun `close continues after ordinary and compatibility failures`() {
        val closed = ArrayList<Int>()
        val resources = listOf(
            AutoCloseable {
                closed += 1
                throw IllegalStateException("close failed")
            },
            AutoCloseable {
                closed += 2
                throw NoSuchMethodError("reader API drift")
            },
            AutoCloseable { closed += 3 },
        )

        closeCatalogResourcesSafely(resources)

        assertEquals(listOf(1, 2, 3), closed)
    }

    @Test
    fun `close does not swallow fatal errors`() {
        assertThrows(AssertionError::class.java) {
            closeCatalogResourcesSafely(listOf(AutoCloseable { throw AssertionError("fatal") }))
        }
    }
}

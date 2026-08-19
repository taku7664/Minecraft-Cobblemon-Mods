package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ManagedOwnerRegistryTest {
    @Test
    fun `registration resolves every managed pokemon and close removes only that owner`() {
        val registry = ManagedOwnerRegistry<String, String>()
        val first = registry.register("trainer-a", setOf("original-a", "effected-a"))
        val second = registry.register("trainer-b", setOf("original-b", "effected-b"))

        assertEquals("trainer-a", registry.resolve("original-a"))
        assertEquals("trainer-a", registry.resolve("effected-a"))
        assertEquals("trainer-b", registry.resolve("original-b"))

        first.close()
        first.close()

        assertNull(registry.resolve("original-a"))
        assertNull(registry.resolve("effected-a"))
        assertEquals("trainer-b", registry.resolve("effected-b"))

        second.close()
    }

    @Test
    fun `conflicting registration is rejected without leaving a partial owner mapping`() {
        val registry = ManagedOwnerRegistry<String, String>()
        val first = registry.register("trainer-a", setOf("shared"))

        assertThrows(IllegalArgumentException::class.java) {
            registry.register("trainer-b", setOf("new", "shared"))
        }

        assertNull(registry.resolve("new"))
        assertEquals("trainer-a", registry.resolve("shared"))
        first.close()
    }
}

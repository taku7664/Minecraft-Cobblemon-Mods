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

    @Test
    fun `server reset removes all managed owners and old handles stay harmless`() {
        val registry = ManagedOwnerRegistry<String, String>()
        val first = registry.register("trainer-a", setOf("a"))
        registry.register("trainer-b", setOf("b"))

        registry.clear()
        first.close()

        assertNull(registry.resolve("a"))
        assertNull(registry.resolve("b"))
    }

    @Test
    fun `overlapping registrations by the same owner remain until every handle closes`() {
        val registry = ManagedOwnerRegistry<String, String>()
        val first = registry.register("trainer-a", setOf("shared", "first-only"))
        val second = registry.register("trainer-a", setOf("shared", "second-only"))

        first.close()

        assertEquals("trainer-a", registry.resolve("shared"))
        assertNull(registry.resolve("first-only"))
        assertEquals("trainer-a", registry.resolve("second-only"))

        second.close()
        assertNull(registry.resolve("shared"))
        assertNull(registry.resolve("second-only"))
    }

    @Test
    fun `handle from before reset cannot remove an equal new owner registration`() {
        data class Owner(val id: String)

        val registry = ManagedOwnerRegistry<String, Owner>()
        val old = registry.register(Owner("trainer-a"), setOf("shared"))
        registry.clear()
        val replacement = registry.register(Owner("trainer-a"), setOf("shared"))

        old.close()

        assertEquals(Owner("trainer-a"), registry.resolve("shared"))
        replacement.close()
        assertNull(registry.resolve("shared"))
    }
}

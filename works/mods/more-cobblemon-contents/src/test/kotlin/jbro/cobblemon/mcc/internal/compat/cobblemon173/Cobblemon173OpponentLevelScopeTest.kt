package jbro.cobblemon.mcc.internal.compat.cobblemon173

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class Cobblemon173OpponentLevelScopeTest {
    @Test
    fun `the raised maximum holds only while the block runs, on its own thread`() {
        assertNull(Cobblemon173OpponentLevelScope.limit())
        val seen = Cobblemon173OpponentLevelScope.during(105) {
            var other: Int? = -1
            Thread { other = Cobblemon173OpponentLevelScope.limit() }.apply { start(); join() }
            assertNull(other)
            Cobblemon173OpponentLevelScope.during(110) { assertEquals(110, Cobblemon173OpponentLevelScope.limit()) }
            Cobblemon173OpponentLevelScope.limit()
        }
        assertEquals(105, seen)
        assertNull(Cobblemon173OpponentLevelScope.limit())
    }

    @Test
    fun `a failing build does not leave the maximum raised`() {
        assertThrows<IllegalStateException> { Cobblemon173OpponentLevelScope.during(105) { error("build failed") } }
        assertNull(Cobblemon173OpponentLevelScope.limit())
    }
}

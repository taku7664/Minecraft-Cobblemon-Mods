package jbro.cobblemon.mcc.api.battle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The `dynamax=none|normal|gmax` mark on a managed team member, read off before Cobblemon parses the rest. */
class ManagedDynamaxTest {
    @Test
    fun `the mark is read off the line and the rest is left as it was`() {
        val (rest, permission) = ManagedDynamax.split("machamp level=80 moves=closecombat gmax_factor=true dynamax=gmax")
        assertEquals("machamp level=80 moves=closecombat gmax_factor=true", rest)
        assertEquals(ManagedDynamax.Permission.GMAX, permission)
        assertEquals(ManagedDynamax.Permission.NORMAL, ManagedDynamax.split("eldegoss level=80 dynamax=Normal").second)
        assertEquals(ManagedDynamax.Permission.NONE, ManagedDynamax.split("dynamax=none hitmontop level=80").second)
    }

    @Test
    fun `a line without a mark has none, and a bad or repeated mark is refused`() {
        assertNull(ManagedDynamax.split("pangoro level=81").second)
        assertThrows(IllegalArgumentException::class.java) { ManagedDynamax.split("pangoro level=81 dynamax=yes") }
        assertThrows(IllegalArgumentException::class.java) { ManagedDynamax.split("pangoro dynamax=gmax dynamax=none") }
        // An unmarked Pokemon (a player's) may always Dynamax.
        assertTrue(ManagedDynamax.permits(null))
    }
}

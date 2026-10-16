package jbro.cobblemon.mcc.api.battle

import jbro.cobblemon.mcc.api.battle.ManagedDynamax.Mark
import jbro.cobblemon.mcc.api.battle.ManagedDynamax.Permission
import jbro.cobblemon.mcc.api.battle.ManagedDynamax.TurnMember
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The `canDynamax=none|normal|gmax` and `dynamaxPriority=N` marks on a managed team member, read off before Cobblemon parses the rest. */
class ManagedDynamaxTest {
    @Test
    fun `the marks are read off the line and the rest is left as it was`() {
        val (rest, mark) = ManagedDynamax.split("machamp level=80 moves=closecombat gmax_factor=true canDynamax=gmax dynamaxPriority=1")
        assertEquals("machamp level=80 moves=closecombat gmax_factor=true", rest)
        assertEquals(Mark(Permission.GMAX, 1), mark)
        assertEquals(Mark(Permission.NORMAL), ManagedDynamax.split("eldegoss level=80 candynamax=Normal").second)
        assertEquals(Mark(Permission.NONE), ManagedDynamax.split("canDynamax=none hitmontop level=80").second)
        assertEquals(Mark(priority = 2), ManagedDynamax.split("hitmontop dynamaxPriority=2 level=80").second)
    }

    @Test
    fun `a line without marks has none, and a bad, repeated or old mark is refused`() {
        assertTrue(ManagedDynamax.split("pangoro level=81").second.isEmpty)
        assertThrows(IllegalArgumentException::class.java) { ManagedDynamax.split("pangoro level=81 canDynamax=yes") }
        assertThrows(IllegalArgumentException::class.java) { ManagedDynamax.split("pangoro canDynamax=gmax canDynamax=none") }
        assertThrows(IllegalArgumentException::class.java) { ManagedDynamax.split("pangoro dynamaxPriority=0") }
        // The old token would otherwise reach Cobblemon's parser unread.
        assertThrows(IllegalArgumentException::class.java) { ManagedDynamax.split("pangoro dynamax=gmax") }
        // An unmarked Pokemon (a player's) may always Dynamax.
        assertTrue(ManagedDynamax.permits(null))
    }

    @Test
    fun `the living member that comes first holds the Dynamax, and the next ones get it once it is gone`() {
        val ace = Mark(Permission.GMAX, 1)
        val second = Mark(priority = 2)
        val rest = Mark()
        // The ace alive: nobody behind it may.
        assertTrue(ManagedDynamax.holdsTurn(ace, listOf(second, rest)))
        assertFalse(ManagedDynamax.holdsTurn(second, listOf(ace, rest)))
        assertFalse(ManagedDynamax.holdsTurn(rest, listOf(ace, second)))
        // The ace knocked out before using it: the next in order may, then the unnumbered ones together.
        assertTrue(ManagedDynamax.holdsTurn(second, listOf(rest)))
        assertTrue(ManagedDynamax.holdsTurn(rest, listOf(Mark())))
        // One kept out never holds it, and does not hold it from the others.
        assertFalse(ManagedDynamax.holdsTurn(Mark(Permission.NONE, 1), listOf(rest)))
        assertTrue(ManagedDynamax.holdsTurn(rest, listOf(Mark(Permission.NONE, 1))))
    }

    @Test
    fun `an explicit maximum priority still comes before an omitted priority`() {
        val explicit = Mark(priority = Int.MAX_VALUE)
        val omitted = Mark()

        assertTrue(ManagedDynamax.holdsTurn(explicit, listOf(omitted)))
        assertFalse(ManagedDynamax.holdsTurn(omitted, listOf(explicit)))
    }

    @Test
    fun `a species or form Cobblemon blocks from Dynamax does not hold the turn`() {
        val blocked = TurnMember(Mark(Permission.NORMAL, 1), canDynamax = false)
        val available = TurnMember(Mark(Permission.NORMAL, 2), canDynamax = true)

        assertTrue(ManagedDynamax.holdsTurn(available, listOf(blocked)))
        assertFalse(ManagedDynamax.holdsTurn(blocked, listOf(available)))
    }
}

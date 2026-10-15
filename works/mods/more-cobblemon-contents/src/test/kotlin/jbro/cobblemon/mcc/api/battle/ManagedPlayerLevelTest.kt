package jbro.cobblemon.mcc.api.battle

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ManagedPlayerLevelTest {
    @Test fun `only overlevelled Pokemon are lowered`() {
        assertEquals(20, ManagedPlayerLevel.forBattle(20, 30))
        assertEquals(30, ManagedPlayerLevel.forBattle(70, 30))
        assertEquals(70, ManagedPlayerLevel.forBattle(70, null))
        assertEquals(1, ManagedPlayerLevel.forBattle(100, 1))
    }
    @Test fun `invalid caps fail before materializing a team`() {
        assertThrows(IllegalArgumentException::class.java) { ManagedPlayerLevel.forBattle(70, 0) }
        assertThrows(IllegalArgumentException::class.java) { ManagedPlayerLevel.forBattle(70, 101) }
    }
    @Test fun `the optional cap preserves existing addon constructor and start signatures`() {
        assertEquals(setOf(13, 15), ManagedPveBattles.Request::class.java.declaredConstructors.map { it.parameterCount }.toSet())
        assertTrue(ManagedPveBattles::class.java.methods.any { it.name == "start" && it.parameterCount == 3 })
        assertTrue(ManagedPveBattles::class.java.methods.any { it.name == "start" && it.parameterCount == 4 })
    }
}

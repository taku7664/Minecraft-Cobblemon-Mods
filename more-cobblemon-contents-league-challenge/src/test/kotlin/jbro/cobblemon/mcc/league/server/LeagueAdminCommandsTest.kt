package jbro.cobblemon.mcc.league.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LeagueAdminCommandsTest {
    @Test
    fun `league operator commands keep their argument trees`() {
        val league = LeagueAdminCommands.build().build()
        assertEquals("league", league.name)
        assertEquals(setOf("inspect", "rewards", "run", "cap", "validate", "catalog", "import-badges", "trainer"), league.children.map { it.name }.toSet())
        assertEquals(setOf("list", "retry", "drop"), league.getChild("rewards").children.map { it.name }.toSet())
        assertEquals(setOf("cancel"), league.getChild("run").children.map { it.name }.toSet())
        assertEquals(setOf("sync"), league.getChild("cap").children.map { it.name }.toSet())
        assertEquals(setOf("spawn", "despawn", "list", "cooldown"), league.getChild("trainer").children.map { it.name }.toSet())
    }
}

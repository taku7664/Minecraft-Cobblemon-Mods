package jbro.cobblemon.mcc.internal.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MccAdminCommandsTest {
    @Test
    fun `status, record reset and battle commands keep their argument trees`() {
        assertEquals("status", MccAdminCommands.status().build().name)

        val records = MccAdminCommands.records().build()
        val reset = records.getChild("reset").getChild("player")
        assertEquals(setOf("content"), reset.children.map { it.name }.toSet())
        assertEquals(setOf("format"), reset.getChild("content").children.map { it.name }.toSet())

        val battle = MccAdminCommands.battle().build()
        assertEquals(setOf("list", "end", "pending"), battle.children.map { it.name }.toSet())
        assertEquals(setOf("forfeit", "void"), battle.getChild("end").getChild("player").children.map { it.name }.toSet())
        assertEquals(setOf("list", "retry", "drop"), battle.getChild("pending").children.map { it.name }.toSet())
        listOf("list", "retry", "drop").forEach { action ->
            assertEquals(setOf("player"), battle.getChild("pending").getChild(action).children.map { it.name }.toSet(), action)
        }
    }
}

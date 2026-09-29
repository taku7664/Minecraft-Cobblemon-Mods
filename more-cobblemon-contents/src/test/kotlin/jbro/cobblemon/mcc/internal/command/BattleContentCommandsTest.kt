package jbro.cobblemon.mcc.internal.command

import jbro.cobblemon.mcc.internal.application.DefaultBattleContentApplicationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test

class BattleContentCommandsTest {
    @Test
    fun `mcc command opens the GUI directly and exposes BP operations`() {
        val root = BattleContentCommands.build(
            DefaultBattleContentApplicationService(emptyList()),
            contributors = emptyList(),
        ).build()

        assertEquals("mcc", root.name)
        assertNotNull(root.command)
        assertEquals(setOf("bp"), root.children.map { it.name }.toSet())

        val bp = root.getChild("bp")
        assertEquals(setOf("get", "history", "add", "remove", "set"), bp.children.map { it.name }.toSet())
        assertEquals(setOf("player"), bp.getChild("get").children.map { it.name }.toSet())
        assertEquals(setOf("player"), bp.getChild("add").children.map { it.name }.toSet())
        assertEquals(setOf("player"), bp.getChild("remove").children.map { it.name }.toSet())
        assertEquals(setOf("player"), bp.getChild("set").children.map { it.name }.toSet())
        assertEquals(2, BattlePointCommands.ADMIN_PERMISSION_LEVEL)
        assertNotSame(bp.requirement, bp.getChild("get").requirement)
        assertNotSame(bp.requirement, bp.getChild("add").requirement)
        assertNotSame(bp.requirement, bp.getChild("remove").requirement)
        assertNotSame(bp.requirement, bp.getChild("set").requirement)
        assertNotSame(
            bp.getChild("history").requirement,
            bp.getChild("history").getChild("player").requirement,
        )

        assertEquals(2, BattleProgressCommands.ADMIN_PERMISSION_LEVEL)
    }
}

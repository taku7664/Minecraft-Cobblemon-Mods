package jbro.cobblemon.morebattlecontent.internal.command

import jbro.cobblemon.morebattlecontent.internal.application.DefaultBattleContentApplicationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test

class BattleContentCommandsTest {
    @Test
    fun `mbc command opens the GUI directly and exposes BP and admin progress operations`() {
        val root = BattleContentCommands.build(DefaultBattleContentApplicationService(emptyList())).build()

        assertEquals("mbc", root.name)
        assertNotNull(root.command)
        assertEquals(setOf("bp", "tower", "factory"), root.children.map { it.name }.toSet())

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

        val towerStreak = root.getChild("tower").getChild("streak")
        assertEquals(setOf("get", "set", "reset"), towerStreak.children.map { it.name }.toSet())
        val towerGet = towerStreak.getChild("get")
        assertEquals(setOf("player"), towerGet.children.map { it.name }.toSet())
        assertEquals(setOf("format"), towerGet.getChild("player").children.map { it.name }.toSet())
        val towerSet = towerStreak.getChild("set")
        assertEquals(setOf("player"), towerSet.children.map { it.name }.toSet())
        assertEquals(
            setOf("format"),
            towerSet.getChild("player").children.map { it.name }.toSet(),
        )
        assertEquals(
            setOf("value"),
            towerSet.getChild("player").getChild("format").children.map { it.name }.toSet(),
        )
        val towerReset = towerStreak.getChild("reset")
        assertEquals(setOf("player"), towerReset.children.map { it.name }.toSet())
        assertEquals(
            setOf("scope"),
            towerReset.getChild("player").getChild("format").children.map { it.name }.toSet(),
        )

        val factoryFloor = root.getChild("factory").getChild("floor")
        assertEquals(setOf("get", "set", "reset"), factoryFloor.children.map { it.name }.toSet())
        val factoryGet = factoryFloor.getChild("get")
        assertEquals(setOf("player"), factoryGet.children.map { it.name }.toSet())
        assertEquals(
            setOf("level_mode"),
            factoryGet.getChild("player").getChild("format").children.map { it.name }.toSet(),
        )
        val factorySet = factoryFloor.getChild("set")
        assertEquals(setOf("player"), factorySet.children.map { it.name }.toSet())
        assertEquals(
            setOf("format"),
            factorySet.getChild("player").children.map { it.name }.toSet(),
        )
        assertEquals(
            setOf("level_mode"),
            factorySet.getChild("player").getChild("format").children.map { it.name }.toSet(),
        )
        assertEquals(
            setOf("value"),
            factorySet.getChild("player").getChild("format").getChild("level_mode").children.map { it.name }.toSet(),
        )
        val factoryReset = factoryFloor.getChild("reset")
        assertEquals(setOf("player"), factoryReset.children.map { it.name }.toSet())
        assertEquals(
            setOf("scope"),
            factoryReset.getChild("player").getChild("format").getChild("level_mode").children.map { it.name }.toSet(),
        )
        assertEquals(2, BattleProgressCommands.ADMIN_PERMISSION_LEVEL)
        assertNotSame(root.getChild("tower").requirement, towerSet.requirement)
        assertNotSame(root.getChild("factory").requirement, factorySet.requirement)
    }
}

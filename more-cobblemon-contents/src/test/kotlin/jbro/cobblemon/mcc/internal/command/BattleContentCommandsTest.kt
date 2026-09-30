package jbro.cobblemon.mcc.internal.command

import jbro.cobblemon.mcc.internal.application.DefaultBattleContentApplicationService
import net.minecraft.commands.CommandSource
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec2
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `every player opens the hub by default while the commands under it stay for operators`() {
        val contributed = object : MccCommandContributor {
            override fun build() = net.minecraft.commands.Commands.literal("status")
        }
        val dir = java.nio.file.Files.createTempDirectory("mcc-command")
        fun source(level: Int) = CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, level, "test",
            Component.literal("test"), null, null)
        fun root() = BattleContentCommands.build(DefaultBattleContentApplicationService(emptyList()), contributors = listOf(contributed)).build()

        jbro.cobblemon.mcc.internal.hub.BattleHubTabConfigFile.load(dir.resolve("default.json"))
        val open = root()
        assertTrue(open.requirement.test(source(0)))
        listOf("bp", "status").forEach { name ->
            assertFalse(open.getChild(name).requirement.test(source(0)), name)
            assertTrue(open.getChild(name).requirement.test(source(2)), name)
        }

        val restricted = dir.resolve("restricted.json")
        java.nio.file.Files.writeString(restricted, """{"command_permission_level": 2}""")
        jbro.cobblemon.mcc.internal.hub.BattleHubTabConfigFile.load(restricted)
        val closed = root()
        assertFalse(closed.requirement.test(source(0)))
        assertTrue(closed.requirement.test(source(2)))
        jbro.cobblemon.mcc.internal.hub.BattleHubTabConfigFile.load(dir.resolve("default.json"))
    }
}

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
    fun `a player can run only the hub and their own BP, and operators everything`() {
        val dir = java.nio.file.Files.createTempDirectory("mcc-command")
        val admin = listOf(MccAdminCommands::status, MccAdminCommands::records, MccAdminCommands::battle).map { make ->
            object : MccCommandContributor {
                override fun build() = make()
            }
        }
        fun root() = BattleContentCommands.build(DefaultBattleContentApplicationService(emptyList()), contributors = admin).build()

        jbro.cobblemon.mcc.internal.hub.BattleHubTabConfigFile.load(dir.resolve("default.json"))
        assertEquals(setOf("mcc", "mcc bp", "mcc bp history", "mcc bp history <count>"), runnable(root(), source(0)))
        val everything = runnable(root(), source(2))
        listOf("mcc status", "mcc records reset <player>", "mcc battle list", "mcc bp add <player> <amount>").forEach {
            assertTrue(it in everything, it)
        }

        val restricted = dir.resolve("restricted.json")
        java.nio.file.Files.writeString(restricted, """{"command_permission_level": 2}""")
        jbro.cobblemon.mcc.internal.hub.BattleHubTabConfigFile.load(restricted)
        assertTrue(runnable(root(), source(0)).isEmpty())
        assertTrue("mcc" in runnable(root(), source(2)))
        jbro.cobblemon.mcc.internal.hub.BattleHubTabConfigFile.load(dir.resolve("default.json"))
    }

    companion object {
        fun source(level: Int) = CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, level, "test",
            Component.literal("test"), null, null)

        /** Every path under [node] that [source] may reach and that runs something, as "mcc bp history <count>". */
        fun runnable(node: com.mojang.brigadier.tree.CommandNode<CommandSourceStack>, source: CommandSourceStack,
                     path: String = ""): Set<String> {
            if (!node.requirement.test(source)) return emptySet()
            val name = if (node is com.mojang.brigadier.tree.ArgumentCommandNode<*, *>) "<${node.name}>" else node.name
            val here = if (path.isEmpty()) name else "$path $name"
            return (if (node.command != null) setOf(here) else emptySet()) + node.children.flatMap { runnable(it, source, here) }
        }
    }
}

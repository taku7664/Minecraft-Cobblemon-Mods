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
        assertEquals(setOf("bp", "terminal"), root.children.map { it.name }.toSet())

        val terminal = root.getChild("terminal")
        assertEquals(setOf("all", "league", "tower", "factory"), terminal.children.map { it.name }.toSet())
        terminal.children.forEach { kind ->
            assertNotNull(kind.command)
            assertNotNull(kind.getChild("player").command)
            assertTrue(kind.requirement.test(source(0)))
            assertFalse(kind.getChild("player").requirement.test(source(0)))
            assertTrue(kind.getChild("player").requirement.test(source(2)))
        }

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
    fun `only operators open terminals by command`() {
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
        listOf("all", "league", "tower", "factory").forEach {
            assertTrue("mcc terminal $it" in everything)
            assertTrue("mcc terminal $it <player>" in everything)
        }

        val restricted = dir.resolve("restricted.json")
        java.nio.file.Files.writeString(restricted, """{"command_permission_level": 2}""")
        jbro.cobblemon.mcc.internal.hub.BattleHubTabConfigFile.load(restricted)
        assertTrue(runnable(root(), source(0)).isEmpty())
        assertTrue("mcc" in runnable(root(), source(2)))
        jbro.cobblemon.mcc.internal.hub.BattleHubTabConfigFile.load(dir.resolve("default.json"))
    }

    @Test
    fun `each terminal command routes to its block id and rejects players`() {
        val dispatcher = com.mojang.brigadier.CommandDispatcher<CommandSourceStack>()
        val calls = mutableListOf<Pair<String, Boolean>>()
        dispatcher.register(net.minecraft.commands.Commands.literal("mcc").then(TerminalCommands.build { _, id, targeted ->
            calls += id.toString() to targeted
            1
        }))
        val expected = linkedMapOf(
            "all" to "more_cobblemon_contents:holo_battle_terminal",
            "league" to "more_cobblemon_contents_league_challenge:league_terminal",
            "tower" to "more_cobblemon_contents_battle_tower:battle_tower_terminal",
            "factory" to "more_cobblemon_contents_battle_factory:battle_factory_terminal",
        )
        expected.forEach { (alias, id) ->
            org.junit.jupiter.api.Assertions.assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException::class.java) {
                dispatcher.execute("mcc terminal $alias", source(0))
            }
            org.junit.jupiter.api.Assertions.assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException::class.java) {
                dispatcher.execute("mcc terminal $alias Alex", source(0))
            }
            assertEquals(1, dispatcher.execute("mcc terminal $alias", source(2)))
            assertEquals(id to false, calls.last())
            assertEquals(1, dispatcher.execute("mcc terminal $alias Alex", source(2)))
            assertEquals(id to true, calls.last())
        }
        assertEquals(8, calls.size)
    }

    @Test
    fun `an absent terminal fails instead of reporting a successful open`() {
        val dispatcher = com.mojang.brigadier.CommandDispatcher<CommandSourceStack>()
        dispatcher.register(net.minecraft.commands.Commands.literal("mcc").then(TerminalCommands.build()))
        val failure = org.junit.jupiter.api.Assertions.assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException::class.java) {
            dispatcher.execute("mcc terminal league", source(2))
        }
        assertTrue(failure.rawMessage.string.contains("command.more_cobblemon_contents.terminal.unavailable"))
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

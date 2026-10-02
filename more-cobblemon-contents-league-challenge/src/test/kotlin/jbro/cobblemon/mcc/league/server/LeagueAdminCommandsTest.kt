package jbro.cobblemon.mcc.league.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LeagueAdminCommandsTest {
    @Test
    fun `league operator commands keep their argument trees`() {
        val league = LeagueAdminCommands.build().build()
        assertEquals("league", league.name)
        assertEquals(setOf("inspect", "rewards", "run", "progress", "test", "cap", "validate", "catalog", "import-badges", "trainer"), league.children.map { it.name }.toSet())
        assertEquals(setOf("list", "retry", "drop"), league.getChild("rewards").children.map { it.name }.toSet())
        assertEquals(setOf("cancel"), league.getChild("run").children.map { it.name }.toSet())
        assertEquals(setOf("set", "reset"), league.getChild("progress").children.map { it.name }.toSet())
        val difficulties = league.getChild("test").getChild("player").getChild("challenge").children.map { it.name }.toSet()
        assertEquals(setOf("ai-입문", "ai-표준", "ai-상급", "ai-보스"), difficulties)
        assertEquals(setOf("sync"), league.getChild("cap").children.map { it.name }.toSet())
        assertEquals(setOf("spawn", "despawn", "list", "cooldown"), league.getChild("trainer").children.map { it.name }.toSet())
    }

    @Test
    fun `players cannot reach the league operator commands`() {
        fun source(level: Int) = net.minecraft.commands.CommandSourceStack(net.minecraft.commands.CommandSource.NULL,
            net.minecraft.world.phys.Vec3.ZERO, net.minecraft.world.phys.Vec2.ZERO, null, level, "test",
            net.minecraft.network.chat.Component.literal("test"), null, null)
        listOf(LeagueAdminCommands.build().build()).forEach { root ->
            org.junit.jupiter.api.Assertions.assertFalse(root.requirement.test(source(0)), root.name)
            org.junit.jupiter.api.Assertions.assertTrue(root.requirement.test(source(2)), root.name)
        }
    }
}

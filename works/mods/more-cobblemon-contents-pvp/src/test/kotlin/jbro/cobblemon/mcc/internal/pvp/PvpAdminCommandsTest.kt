package jbro.cobblemon.mcc.internal.pvp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PvpAdminCommandsTest {
    @Test
    fun `pvp operator commands keep their argument trees`() {
        val pvp = PvpAdminCommands.build().build()
        assertEquals("pvp", pvp.name)
        assertEquals(setOf("rooms", "room", "challenge", "arena", "lounge"), pvp.children.map { it.name }.toSet())
        assertEquals(setOf("close", "kick"), pvp.getChild("room").children.map { it.name }.toSet())
        assertEquals(setOf("list", "release"), pvp.getChild("arena").children.map { it.name }.toSet())
        assertEquals(setOf("rescue"), pvp.getChild("lounge").children.map { it.name }.toSet())
        assertEquals(setOf("cancel"), pvp.getChild("challenge").children.map { it.name }.toSet())
    }

    @Test
    fun `players cannot reach the PvP operator commands`() {
        fun source(level: Int) = net.minecraft.commands.CommandSourceStack(net.minecraft.commands.CommandSource.NULL,
            net.minecraft.world.phys.Vec3.ZERO, net.minecraft.world.phys.Vec2.ZERO, null, level, "test",
            net.minecraft.network.chat.Component.literal("test"), null, null)
        listOf(PvpAdminCommands.build().build()).forEach { root ->
            org.junit.jupiter.api.Assertions.assertFalse(root.requirement.test(source(0)), root.name)
            org.junit.jupiter.api.Assertions.assertTrue(root.requirement.test(source(2)), root.name)
        }
    }
}

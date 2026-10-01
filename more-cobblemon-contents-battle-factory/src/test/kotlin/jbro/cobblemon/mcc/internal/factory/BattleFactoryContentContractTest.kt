package jbro.cobblemon.mcc.internal.factory

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BattleFactoryContentContractTest {
    @Test
    fun `factory floor admin command keeps its argument tree`() {
        val root = FactoryProgressCommands.build().build()

        assertEquals("factory", root.name)
        val factoryFloor = root.getChild("floor")
        assertEquals(setOf("get", "set", "reset"), factoryFloor.children.map { it.name }.toSet())
        assertEquals(setOf("player"), root.getChild("session").children.map { it.name }.toSet())
        assertEquals(setOf("force"), root.getChild("abandon").getChild("player").children.map { it.name }.toSet())
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
        assertNotSame(root.requirement, factorySet.requirement)
    }

    @Test
    fun `factory battles and catalog cleanup go through the core`() {
        val runtime = source("internal/compat/cobblemon173/Cobblemon173FactoryPveBattleRuntime.kt")
        assertTrue(runtime.contains("Cobblemon173ManagedAiBattleEngine("))
        assertTrue(runtime.contains("engine.start("))
        assertTrue(
            source("internal/compat/fabric/FactoryCatalogResources.kt")
                .contains("ManagedServerEphemeralStateCleanup.register(store::clear)"),
        )
    }

    private fun source(path: String): String = Files.readString(Path.of("src/main/kotlin/jbro/cobblemon/mcc", path))

    @Test
    fun `players cannot reach the factory operator commands`() {
        fun source(level: Int) = net.minecraft.commands.CommandSourceStack(net.minecraft.commands.CommandSource.NULL,
            net.minecraft.world.phys.Vec3.ZERO, net.minecraft.world.phys.Vec2.ZERO, null, level, "test",
            net.minecraft.network.chat.Component.literal("test"), null, null)
        listOf(FactoryProgressCommands.build().build()).forEach { root ->
            org.junit.jupiter.api.Assertions.assertFalse(root.requirement.test(source(0)), root.name)
            org.junit.jupiter.api.Assertions.assertTrue(root.requirement.test(source(2)), root.name)
        }
    }

    @Test
    fun `the Battle Factory terminal cannot be crafted`() {
        // Terminals are placed by operators; a crafting recipe would let anyone set one up.
        org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.exists(java.nio.file.Path.of("src/main/resources/data/more_cobblemon_contents_battle_factory/recipe/battle_factory_terminal.json")))
    }
}

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
}
